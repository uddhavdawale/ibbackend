package org.ib.ibbackend.controller;

import org.ib.ibbackend.entity.Tenant;
import org.ib.ibbackend.entity.User;
import org.ib.ibbackend.service.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/test")
@CrossOrigin(originPatterns  = "*")

public class JwtTestController {
    @Autowired
    private JwtService jwtService;

    @GetMapping("/generate")
    public String generate() {
      //  return jwtService.generateToken("uddhav", 1L, "ADMIN");
       User usr=new User();
       usr.setRole("STORE_ADMIN");
        Tenant tnt=new Tenant();
        tnt.setId(1);
        usr.setTenant(tnt);

        usr.setEmail("uddhavdawale2@gmail.com");
        //usr.setTenantId(1L);


        return jwtService.generateToken(usr); }


}