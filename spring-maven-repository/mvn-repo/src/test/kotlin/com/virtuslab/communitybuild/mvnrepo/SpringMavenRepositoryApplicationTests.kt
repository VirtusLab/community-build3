package com.virtuslab.communitybuild.mvnrepo;

import com.virtuslab.communitybuild.mvnrepo.dependency.DependencyController;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SpringMavenRepositoryApplicationTests {
    @Autowired
    private lateinit var controller: DependencyController;

    @Test
    fun contextLoads() {
    }
}
