package xyz.wewin.autumn.data.using.demo.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import xyz.wewin.autumn.data.jdbc.page.Page;
import xyz.wewin.autumn.data.jdbc.page.Pageable;
import xyz.wewin.autumn.data.using.demo.Order;
import xyz.wewin.autumn.data.using.demo.OrderRepository;
import xyz.wewin.autumn.data.using.demo.User;
import xyz.wewin.autumn.data.using.demo.UserRepository;

/**
 *
 * @author: baoxin.zhao
 * @date: 2026/11/1
 */
@RestController
@RequestMapping("/training")
public class TrainingController {
    private Logger log = LoggerFactory.getLogger(this.getClass());
    @Autowired
    UserRepository userRepository;

    @Autowired
    OrderRepository orderRepository;


    @GetMapping("/user")
    public List<User> getUser(){
        List<User> userList = userRepository.findAll();
        log.info("userList:{}", userList);
        return userList;
    }

    /** 分页：按主键顺序取一页并回查总行数；每个用户的 orders 由第二条 IN 查询装配。 */
    @GetMapping("/user/page")
    public Page<User> pageUser(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "2") int size) {
        Page<User> result = this.userRepository.findAll(Pageable.of(page, size));
        log.info("page:{} size:{} total:{} totalPages:{}", page, size, result.total(), result.totalPages());
        return result;
    }

    /**
     * 批量写：saveAll 走一条批量 INSERT（自增主键逐个回填）。
     *
     * <p>写的是示例自有表 demo_order：demo profile 下的 basic_user 属于安全项目的真实表，
     * 演示不往别人的表里插行。</p>
     */
    @PostMapping("/order/batch")
    public List<Order> batchOrder(@RequestParam(defaultValue = "3") int count,
            @RequestParam(defaultValue = "1") long userId) {
        List<Order> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Order order = new Order();
            order.setUserId(userId);
            order.setOrderNo("BULK-" + System.nanoTime() + "-" + i);
            order.setAmount(new BigDecimal("9.90"));
            orders.add(order);
        }
        List<Order> saved = this.orderRepository.saveAll(orders);
        log.info("batch inserted order ids:{}", saved.stream().map(Order::getId).toList());
        return saved;
    }

}
