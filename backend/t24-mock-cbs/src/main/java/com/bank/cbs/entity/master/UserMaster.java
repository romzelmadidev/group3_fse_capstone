package com.bank.cbs.entity.master;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

@Entity
// Read-only: v_all_users unions customers (users) and staff (admin_users) so staff can act as reversal maker/checker.
@Table(name = "v_all_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMaster {

    @Id
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "role", length = 20, nullable = false)
    private String role;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
