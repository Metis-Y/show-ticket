package com.showticket.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.showticket.dto.LoginFormDTO;
import com.showticket.dto.Result;
import com.showticket.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, String clientIp);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();
}
