package xyz.wewin.autumn.data.using;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import xyz.wewin.autumn.data.using.demo.Order;
import xyz.wewin.autumn.data.using.demo.OrderRepository;
import xyz.wewin.autumn.data.using.demo.User;
import xyz.wewin.autumn.data.using.demo.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事务验证：生成的实现类不自己开关连接，全部经 Spring 的 DataSourceUtils 取连接，
 * 因此 @Transactional / TransactionTemplate 一旦包裹，读写自动加入同一事务，
 * 回滚由 spring-tx 默认行为提供，无需 autumn 侧任何适配代码。
 *
 * <p>本类故意不加类级 {@code @Transactional}：事务边界由用例内部显式给出，
 * 才能同时演示「提交后可见」与「异常回滚」。</p>
 */
@SpringBootTest(classes = UsingApplication.class)
class TransactionParticipationTest {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void generatedWritesCommitWithTheSurroundingTransaction() {
		TransactionTemplate tx = new TransactionTemplate(this.transactionManager);
		long usersBefore = this.userRepository.count();
		long ordersBefore = this.orderRepository.count();

		CommitResult result = tx.execute(status -> {
			List<User> saved = this.userRepository.saveAll(List.of(newUser("tx-a"), newUser("tx-b")));
			// 同一个连接、同一个事务：批量写入的键回填 + 自己刚写的行立刻可读
			return new CommitResult(TransactionSynchronizationManager.isActualTransactionActive(), saved.size(),
					this.userRepository.count());
		});

		assertThat(result.activeInsideTx()).isTrue();
		assertThat(result.saved()).isEqualTo(2);
		assertThat(result.usersInsideTx()).isEqualTo(usersBefore + 2);

		// 提交后：另一个事务看见同样的行（说明是真提交，不是同一连接的假象）
		Long visibleAfterCommit = tx.execute(status -> this.userRepository.count());
		assertThat(visibleAfterCommit).isEqualTo(usersBefore + 2);

		// 还原种子数据，避免污染共享同一上下文的其它用例
		tx.execute(status -> {
			this.userRepository.deleteAll(this.userRepository.findAll()
				.stream()
				.filter(user -> user.getUsername().startsWith("tx-"))
				.toList());
			return null;
		});
		assertThat(this.userRepository.count()).isEqualTo(usersBefore);
		assertThat(this.orderRepository.count()).isEqualTo(ordersBefore);
	}

	@Test
	void runtimeExceptionRollsBackEveryStatement() {
		TransactionTemplate tx = new TransactionTemplate(this.transactionManager);
		long usersBefore = this.userRepository.count();
		long ordersBefore = this.orderRepository.count();

		assertThatThrownBy(() -> tx.execute(status -> {
			List<User> saved = this.userRepository.saveAll(List.of(newUser("tx-rollback-a"), newUser("tx-rollback-b")));
			// 子表也写：一次批量 + 一次单条，都应随外层事务一起回滚
			this.orderRepository.saveAll(List.of(newOrder(saved.get(0).getId(), "RB-1")));
			this.orderRepository.save(newOrder(saved.get(1).getId(), "RB-2"));
			throw new IllegalStateException("boom");
		})).isInstanceOf(IllegalStateException.class).hasMessage("boom");

		assertThat(this.userRepository.count()).isEqualTo(usersBefore);
		assertThat(this.orderRepository.count()).isEqualTo(ordersBefore);
		// 主键不会留下半提交的数据，按姓名也查不到
		assertThat(this.userRepository.findAll()).extracting(User::getUsername)
			.doesNotContain("tx-rollback-a", "tx-rollback-b");
	}

	private static User newUser(String username) {
		User user = new User();
		user.setUsername(username);
		user.setEmail(username + "@example.com");
		return user;
	}

	private static Order newOrder(Long userId, String orderNo) {
		Order order = new Order();
		order.setUserId(userId);
		order.setOrderNo(orderNo);
		order.setAmount(new BigDecimal("1.00"));
		return order;
	}

	private record CommitResult(boolean activeInsideTx, int saved, long usersInsideTx) {
	}

}
