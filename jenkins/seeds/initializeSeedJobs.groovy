def runBuildScript = new File("/var/jenkins_home/seeds/runBuild.groovy").text
def runBuildToken = System.getenv("BUILD_TOKEN")

def getConfigContentOrEmpty(String filename) {
    def configsDir = "/var/jenkins_home/build-configs/"
    def path = configsDir + filename
    try {
        return new File(path).text
    } catch(Exception ex) {
        println("Not found config file " + path)
        return ""
    }
}

def projectsConfig =         getConfigContentOrEmpty("projects-config.conf")
def filteredProjectsConfig = getConfigContentOrEmpty("filtered-projects.txt")
def requiredProjectsConfig = getConfigContentOrEmpty("required-projects.txt")
def replacedProjectsConfig = getConfigContentOrEmpty("replaced-projects.txt")

pipelineJob('/runBuild') {
    authenticationToken(runBuildToken)
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
        stringParam("scalaBinaryVersion", "3", "Scala binary version following Scaladex API convention used for detecting projects to be built")
        stringParam("minStarsCount", "100", "Minimal number of start on GitHub required to include a project into the build plan")
        stringParam("maxProjectsCount", "200", "Maximal number of projects to include into the build plan")
        stringParam("requiredProjects", requiredProjectsConfig, "Comma-sepatrated list of projects that have to be included into the build plan (using GitHub coordinates), e.g. 'typelevel/cats,scalaz/scalaz'")
        textParam("replacedProjects", replacedProjectsConfig, "Mapping specifying which projects should be replaced by their forks. Each line in format: <original_org>/<original_name> <new_org>/<new_name> [<new_branch_name>], e.g. 'scalaz/scalaz dotty-staging/scalaz' or 'milessabin/shapeless dotty-staging/shapeless shapeless-3-staging'. Lines which are empty or start with # are ignored")
        textParam("projectsConfig", projectsConfig, "Configuration of project specific settings in the HOCOON format. Used only when project does not contain `scala3-community-build.conf` file")
        textParam("projectsFilters", filteredProjectsConfig, "List of regex patterns used for exclusion of projects from build plan. Each entry should be put in seperate lines, it would be used to match the pattern <org>:<project_or_module>:<version> e.g. foo:bar:0.0.1-RC1.")
        separator {
            name("COMMUNITY_PROJECT")
            sectionHeader("Community project")
            separatorStyle("")
            sectionHeaderStyle("")
        }
        stringParam("enforcedSbtVersion", "1.6.2", "(Optional): Version of sbt to be used when project is using unsupported version of the sbt (older then 1.5.5). If left empty projects using incompatible version would fail. Has no effect on projects using version newer then minimal supported")
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
}

def runBuildWeeklyScript = new File("/var/jenkins_home/seeds/runBuildWeekly.groovy").text

pipelineJob('/runBuildWeekly') {
  definition {
    cps {
      script(runBuildWeeklyScript)
      sandbox()
    }
  }
  properties{
    pipelineTriggers{
      triggers{
        parameterizedCron {
          parameterizedSpecification('''
            # Run full build every Friday at 8 PM
            TZ=Europe/Warsaw
            H 20 * * 5
            ''')
        }
      }
    }
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
        stringParam("scalaBinaryVersion", "3", "Scala binary version following Scaladex API convention used for detecting projects to be built")
        stringParam("minStarsCount", "100", "Minimal number of start on GitHub required to include a project into the build plan")
        stringParam("maxProjectsCount", "40", "Maximal number of projects to include into the build plan")
        stringParam("requiredProjects", requiredProjectsConfig, "Comma-sepatrated list of projects that have to be included into the build plan (using GitHub coordinates), e.g. 'typelevel/cats,scalaz/scalaz'")
        textParam("replacedProjects", replacedProjectsConfig, "Mapping specifying which projects should be replaced by their forks. Each line in format: <original_org>/<original_name> <new_org>/<new_name> [<new_branch_name>], e.g. 'scalaz/scalaz dotty-staging/scalaz' or 'milessabin/shapeless dotty-staging/shapeless shapeless-3-staging'. Lines which are empty or start with # are ignored")
        textParam("projectsConfig", projectsConfig, "Configuration of project specific settings in the HOCOON format. Used only when project does not contain `scala3-community-build.conf` file.")
        textParam("projectsFilters", filteredProjectsConfig, "List of regex patterns used for exclusion of projects from build plan. Each entry should be put in seperate lines, it would be used to match the pattern <org>:<project_or_module>:<version> e.g. foo:bar:0.0.1-RC1.")
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
        stringParam("projectConfig")
        stringParam("repoUrl")
        stringParam("revision")
        stringParam("javaVersion")
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
