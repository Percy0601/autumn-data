# 一键跑 autumn-data-benchmark：编译源码 → 校验正确性 → 跑 JMH
#
# 用法：
#   pwsh -File .\run-benchmark.ps1                 # 跑全部基准
#   pwsh -File .\run-benchmark.ps1 -Filter QueryBenchmark
#   pwsh -File .\run-benchmark.ps1 -Filter QueryBenchmark -ExtraArgs '-wi 1 -i 2 -w 300ms -r 300ms -f 1'
#
# 注意 1：classpath 必须由各模块的 target/classes 拼出来，不能用 ~/.m2 里的快照 jar，
#         否则跑的是上一次 install 的旧代码（本项目 snapshot 不随源码自动更新）。
# 注意 2：混合核 CPU（如 12 代酷睿 P-core + E-core）必须固定亲和性到 P-core。
#         E-core 上同一份代码慢约 1.5 倍，不固定时不同运行之间绝对值可漂 3-4 倍。
#         默认 0xFFF = 逻辑 CPU 0-11（i7-12650H 的 6 个 P-core 及其超线程），
#         子进程（JMH fork 出的 JVM）继承父进程的亲和性。传 -AffinityMask '' 可不固定。

param(
	[string]$MavenHome = 'D:\opt\apache-maven-3.9.12',
	[string]$JavaHome = 'D:\opt\graalvm-25.4.4.1.1+1.1',
	[string]$Filter = '',
	[string]$AffinityMask = '0xFFF',
	[switch]$SkipVerify,
	[string[]]$ExtraArgs = @('-wi 5', '-i 8', '-w 1s', '-r 1s', '-f 2')
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$module = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $module
$mvn = Join-Path $MavenHome 'bin\mvn.cmd'
$java = Join-Path $JavaHome 'bin\java.exe'

Write-Host '[1/4] 编译各模块（-am 保证用源码而不是旧 jar）'
& $mvn -o -q -f (Join-Path $root 'pom.xml') -pl autumn-data-benchmark -am -DskipTests compile
if ($LASTEXITCODE -ne 0) { throw 'compile failed' }

Write-Host '[2/4] 生成第三方依赖 classpath'
Push-Location $module
try {
	& $mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
	if ($LASTEXITCODE -ne 0) { throw 'dependency:build-classpath failed' }

	$libs = ((Get-Content -LiteralPath 'target/cp.txt' -Raw) -split ';') |
		Where-Object { $_ -ne '' -and $_ -notmatch 'autumn-data-' }
	$sourceDirs = @(
		(Join-Path $module 'target\classes'),
		(Join-Path $root 'autumn-data-common\target\classes'),
		(Join-Path $root 'autumn-data-jdbc\target\classes'),
		(Join-Path $root 'autumn-data-meta-jdbc\target\classes')
	)
	$cp = ($sourceDirs -join ';') + ';' + ($libs -join ';')

	if (-not $SkipVerify) {
		Write-Host '[3/4] 校验四条路径结果一致'
		& $java -cp $cp xyz.wewin.autumn.data.bench.BenchVerification
		if ($LASTEXITCODE -ne 0) { throw 'verification failed: 各路径结果不一致，基准数据无意义' }
	}

	Write-Host "[4/4] 运行 JMH（参数：$($ExtraArgs -join ' ')）"
	if ($AffinityMask) {
		(Get-Process -Id $PID).ProcessorAffinity = [IntPtr]([Convert]::ToInt64($AffinityMask, 16))
		Write-Host "      亲和性固定为 0x$AffinityMask（子进程继承）"
	}
	# 既支持 "-wi 3 -i 5 -w 500ms" 这种一整串写法，也支持 '-wi','3','-i','5' 这种分词写法
	$extra = @($ExtraArgs) | ForEach-Object { $_ -split '\s+' } | Where-Object { $_ -ne '' }
	$jmhArgs = @('-cp', $cp, 'org.openjdk.jmh.Main') + $extra + @('-rf', 'json', '-rff', 'target/jmh-results.json')
	if ($Filter) { $jmhArgs += $Filter }
	& $java @jmhArgs
	if ($LASTEXITCODE -ne 0) { throw 'jmh failed' }
	Write-Host '结果：target/jmh-results.json'
}
finally {
	Pop-Location
}
