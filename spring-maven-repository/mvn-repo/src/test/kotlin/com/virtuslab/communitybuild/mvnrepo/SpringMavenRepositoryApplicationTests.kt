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

    @Test
    fun resolve1() {
        assertThat(controller.fetchDependency("/com/eed3si9n/sjson-new-scalajson_2.12/0.9.1/sjson-new-scalajson_2.12-0.9.1.pom")).isNotNull();
    }

}
