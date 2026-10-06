package com.ticketing.orderservice.point;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "point_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointAccount {

    @Id
    @Column(name = "user_id")
    private Long userId;

    // Only changed through PointAccountRepository's atomic UPDATEs, never by setting the field.
    @Column(nullable = false)
    private Long balance;

    public PointAccount(Long userId) {
        this.userId = userId;
        this.balance = 0L;
    }
}
