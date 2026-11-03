package com.ticketing.authservice.internal;

import com.ticketing.authservice.internal.dto.UserInternalResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 사이 호출 전용이다. 공개 게이트웨이를 거치지 않고 docker 네트워크 안에서 직접 닿는다. */
@RestController
@RequestMapping("/internal/users")
public class UserInternalController {

    private final UserInternalService userInternalService;

    public UserInternalController(UserInternalService userInternalService) {
        this.userInternalService = userInternalService;
    }

    // {userId} 매핑보다 앞에 선언했고, 어차피 고정 문자열 세그먼트가 경로 변수보다 항상 우선한다.
    @GetMapping("/lookup")
    public UserInternalResponse lookup(@RequestParam String email) {
        return userInternalService.lookupByEmail(email.trim());
    }

    @GetMapping("/{userId}")
    public UserInternalResponse getUser(@PathVariable Long userId) {
        return userInternalService.getUser(userId);
    }
}
