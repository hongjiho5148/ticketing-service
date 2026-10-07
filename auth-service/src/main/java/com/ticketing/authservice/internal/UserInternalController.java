package com.ticketing.authservice.internal;

import com.ticketing.authservice.internal.dto.UserInternalResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only - not routed through the public Gateway, reached directly within the docker network. */
@RestController
@RequestMapping("/internal/users")
public class UserInternalController {

    private final UserInternalService userInternalService;

    public UserInternalController(UserInternalService userInternalService) {
        this.userInternalService = userInternalService;
    }

    // Declared before the {userId} mapping, and a literal segment always outranks a path variable anyway.
    @GetMapping("/lookup")
    public UserInternalResponse lookup(@RequestParam String email) {
        return userInternalService.lookupByEmail(email.trim());
    }

    @GetMapping("/{userId}")
    public UserInternalResponse getUser(@PathVariable Long userId) {
        return userInternalService.getUser(userId);
    }
}
