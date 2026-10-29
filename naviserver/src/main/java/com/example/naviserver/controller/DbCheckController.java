package com.example.naviserver.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DbCheckController {
    private final JdbcTemplate jdbcTemplate;

    public DbCheckController(JdbcTemplate jdbcTemplate){
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/api/db/check")
    public Integer linkCount(){
        return jdbcTemplate.queryForObject("SELECT count(*) FROM a2_link", Integer.class);
    }


}
