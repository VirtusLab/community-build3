def runBuildScript = new File("/var/jenkins_home/seeds/runBuild.groovy").text
def buildCronTrigger = System.getenv("BUILD_CRON_TRIGGER") ?: ""

pipelineJob('/runBuild') {
    definition {
        cps {
            script(runBuildScript)
            sandbox()
        }
    }
    parameters {
        stringParam("buildName", null, "(Optional) Should be unique among all builds; Should be valid both as a file name and a part of a URL; Will be synthesized from current date and build number if not specified")
        separator {
            name("COMPILER")
            sectionHeader("Compiler")
            separatorStyle("")
            sectionHeaderStyle("")
        }
        stringParam("publishedScalaVersion", null, "(Optional, for debugging purposes mainly): An already published version of the compiler to be used instead of building one from sources, e.g. '3.1.1-RC1-bin-20210904-a82a1a6-NIGHTLY'. When specified, the remaining parameters from this section are ignored")
        stringParam("scalaRepoUrl", "https://github.com/lampepfl/dotty.git")
        stringParam("scalaRepoBranch", "main")
        separator {
            name("BUILD_PLAN")
            sectionHeader("Build plan")
            separatorStyle("")
            sectionHeaderStyle("")
        }
        stringParam("precomputedBuildPlan", null, "(Optional, for debugging purposes mainly): The build plan (in JSON format) to be used instead of computing the plan dynamically. When specified, the remaining parameters from this section are ignored")
        stringParam("scalaBinaryVersionSeries", "3.x", "Scala binary version following Scaladex API convention used for detecting projects to be built")
        stringParam("minStarsCount", "100", "Minimal number of start on GitHub required to include a project into the build plan")
        stringParam("maxProjectsCount", "40", "Maximal number of projects to include into the build plan")
        stringParam("requiredProjects", "", "Comma-sepatrated list of projects that have to be included into the build plan (using GitHub coordinates), e.g. 'typelevel/cats,scalaz/scalaz'")
        textParam("replacedProjects", "", "Mapping specifying which projects should be replaced by their forks. Each line in format: <original_org>/<original_name> <new_org>/<new_name> [<new_branch_name>], e.g. 'scalaz/scalaz dotty-staging/scalaz' or 'milessabin/shapeless dotty-staging/shapeless shapeless-3-staging'. Lines which are empty or start with # are ignored")
        separator {
            name("COMMUNITY_PROJECT")
            sectionHeader("Community project")
            separatorStyle("")
            sectionHeaderStyle("")
        }
        stringParam("enforcedSbtVersion", null, "(Optional): When not specified original sbt versions specified in the build definition of each project will be used")
        separator {
            name("GENERAL")
            sectionHeader("General")
            separatorStyle("")
            sectionHeaderStyle("")
        }
        stringParam("mvnRepoBaseUrl", "https://mvn-repo:8081/maven2")
        separator {
            name("STATISTICS")
            sectionHeader("Statistics")
            separatorStyle("")
            sectionHeaderStyle("")
        }
        stringParam("elasticSearchUrl", "https://community-build-es-http:9200")
        stringParam("elasticSearchUserName", "elastic")
        stringParam("elasticSearchSecretName", "community-build-es-elastic-user")
    }
    triggers {
        cron(buildCronTrigger)
    }
}


def computeBuildPlanScript = new File("/var/jenkins_home/seeds/computeBuildPlan.groovy").text

pipelineJob('/computeBuildPlan') {
    definition {
        cps {
            script(computeBuildPlanScript)
            sandbox()
        }
    }
    properties {
        copyArtifactPermission {
            projectNames("*")
        }
    }
    parameters {
        stringParam("buildName")
        stringParam("scalaBinaryVersionSeries", "3.x", "Scala binary version following Scaladex API convention used for detecting projects to be built")
        stringParam("minStarsCount", "100", "Minimal number of start on GitHub required to include a project into the build plan")
        stringParam("maxProjectsCount", "40", "Maximal number of projects to include into the build plan")
        stringParam("requiredProjects", "", "Comma-sepatrated list of projects that have to be included into the build plan (using GitHub coordinates), e.g. 'typelevel/cats,scalaz/scalaz'")
        textParam("replacedProjects", "", "Mapping specifying which projects should be replaced by their forks. Each line in format: <original_org>/<original_name> <new_org>/<new_name> [<new_branch_name>], e.g. 'scalaz/scalaz dotty-staging/scalaz' or 'milessabin/shapeless dotty-staging/shapeless shapeless-3-staging'. Lines which are empty or start with # are ignored")
    }
}


def buildCompilerScript = new File("/var/jenkins_home/seeds/buildCompiler.groovy").text

pipelineJob('/buildCompiler') {
    definition {
        cps {
            script(buildCompilerScript)
            sandbox()
        }
    }
    properties {
        copyArtifactPermission {
            projectNames("*")
        }
    }
    parameters {
        stringParam("buildName")
        stringParam("scalaRepoUrl", "https://github.com/lampepfl/dotty.git")
        stringParam("scalaRepoBranch", "main")
        stringParam("mvnRepoUrl")
    }
}


def buildCommunityProjectScript = new File("/var/jenkins_home/seeds/buildCommunityProject.groovy").text

pipelineJob('/buildCommunityProject') {
    definition {
        cps {
            script(buildCommunityProjectScript)
            sandbox()
        }
    }
    parameters {
        stringParam("buildName")
        stringParam("projectName")
        stringParam("repoUrl")
        stringParam("revision")
        stringParam("scalaVersion")
        stringParam("version")
        stringParam("targets")
        stringParam("enforcedSbtVersion")
        stringParam("mvnRepoUrl")
        stringParam("elasticSearchUrl")
        stringParam("elasticSearchUserName")
        stringParam("elasticSearchSecretName")
        stringParam("upstreamProjects")
        stringParam("downstreamProjects")
    }
}
