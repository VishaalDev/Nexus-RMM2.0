package com.nexusrmm.repository;

import com.nexusrmm.entity.Computer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComputerRepository extends JpaRepository<Computer, String> {
}
