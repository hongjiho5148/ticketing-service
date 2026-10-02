package com.ticketing.authservice.internal;

import com.ticketing.authservice.internal.dto.UserInternalResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only - not routed through the public Gateway, reached directly within the docker network. */
@RestController
@RequestMapping("/internal/users")
public class UserInternalController {

    private final UserInternalService userInternalService;

    public UserInternalController(UserInternalService userInternalService) {
        this.userInternalService = userInternalService;
    }

    @GetMapping("/{userId}")
    public UserInternalResponse getUser(@PathVariable Long userId) {
        return userInternalService.getUser(userId);
    }
}
