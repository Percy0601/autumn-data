package xyz.wewin.autumn.data.using.demo.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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


    @GetMapping("/user")
    public List<User> getUser(){
        List<User> userList = userRepository.findAll();
        log.info("userList:{}", userList);
        return userList;
    }




}
