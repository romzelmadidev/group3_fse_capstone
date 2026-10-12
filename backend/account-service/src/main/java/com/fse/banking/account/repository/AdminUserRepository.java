package com.fse.banking.account.repository;

import com.fse.banking.account.model.AdminUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AdminUserRepository extends JpaRepository<AdminUserEntity, String> {

    Optional<AdminUserEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}