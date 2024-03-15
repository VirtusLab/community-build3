# Scala 3 Open Community Build

It's a project dedicated to testing new versions of the compiler against the existing Scala 3 ecosystem. 

You can read more about its goals and initial implementation in these articles:
- https://virtuslab.com/blog/prevent-scala3-compiler-regressions-community-build/
- https://virtuslab.com/blog/how-to-be-a-part-of-scalas-open-community-build/


## Including projects in the Open Community Build

The Open Community Build consists of an automated gathering of information about available Scala 3 projects, using the information available from Scaladex and Maven repositories. Every project listed in Scaladex would be included in the candidates for the builds. The not-yet-published libraries or non-library open-source projects can be added to [custom projects list](
https://github.com/VirtusLab/community-build3/blob/master/coordinator/configs/custom-projects.txt) using the GitHub coordinates.

Some of the projects might be filtered out if they're non-compliant, eg. they have a build issue, require a special environment, or don't compile with any recent version of Scala for a long time (are unmaintained). Such projects are [listed in dedicated file](
https://github.com/VirtusLab/community-build3/blob/master/coordinator/configs/filtered-projects.txt) The project might be removed from this list and reintroduced to the weekly builds when it becomes compliant again, which in most cases involves fixing build issues.

Each project might define its config (HOCON) describing how should we handle it, eg. which JDK version to use, exclude some legacy modules, add some sbt options, or disable compilation/running tests if they're buggy/flaky. It can be defined in the project top-level file using a file named ``scala3-community-build.conf` or in our [internal config](https://github.com/VirtusLab/community-build3/blob/master/coordinator/configs/projects-config.conf)

Based on all of these we create a build plan enlisting projects which we should run, and their final config used when compiling them. We do this automatically every Friday few hours before running the weekly build.

The final configuration of all the projects can be found in a dedicated file [buildConfig.json](https://github.com/VirtusLab/community-build3/blob/master/.github/workflows/buildConfig.json) 



## Executing community build
Execution of the community build is handled by the GitHub Actions CI using dedicated jobs. Due to the limitations of the GitHub actions a single job can handle up to 1000 community projects, to test more projects, we split them into multiple jobs with a suffix in the form of a single subsequent alphabet character. Based on the popularity of projects expressed in the amount of GitHub stars first 1000 projects are included in community build A, the next 1000 projects in build B, etc. 

The list of all included projects and their order of execution can be found in automatically updated build plans: 
- [Build Plan A](https://github.com/VirtusLab/community-build3/blob/master/.github/workflows/buildPlan-A.yaml)
- [Build Plan B](https://github.com/VirtusLab/community-build3/blob/master/.github/workflows/buildPlan-B.yaml)



> **The execution of the workflow jobs requires explicit access**, these can be granted by the VirtusLab compiler team. If you don't have access to running GitHub Actions with the jobs listed below contact @WojciechMazur

 
### Scheduled 
- [Scheduled Build A](https://github.com/VirtusLab/community-build3/actions/workflows/buildExecuteScheduledWeekly-A.yaml)
- [Scheduled Build B](https://github.com/VirtusLab/community-build3/actions/workflows/buildExecuteScheduledWeekly-A.yaml)

The Open Community Build is executed weekly on Friday night using the latest available nightly version. The results of the builds can be found at https://virtuslab.github.io/community-build3/

### On-demand builds
- [Custom Build A](https://github.com/VirtusLab/community-build3/actions/workflows/buildExecuteCustom-A.yaml)
- [Custom Build B](https://github.com/VirtusLab/community-build3/actions/workflows/buildExecuteCustom-B.yaml)

The compiler team might be interested in executing on-demand builds with a configured version of Scala to use. For that we can use a dedicated CI workflow run taking a set of arguments:
- **Custom name of the job** (Optional) - a custom string allowing identification results of the job, would be also used as a buildId when persisting the results in the database. 
- **Published Scala version to use** (Optional) - if non-empty the value would be used to determine an already published version to use, otherwise if left empty, the compiler would be built using the next 2 options.
- **GitHub repository for compiler** (defaults to `scala/scala3`) - when building the compiler this repository would be used to provide the compiler sources
- **GitHub repository branch** (defaults to `main`) - when building the compiler this value would point to the branch in the repository provided in the previous setting, that should be used to build the compiler in the
- **List of scalacOptions to include** (Optional) - a comma-delimited list of Scala compiler options to apply for all projects that would be built (best effort, applies only to sbt and scala-cli currently)
- **List of scalacOptions to exclude** (Optional) - a comma-delimited list of Scala compiler options to remove for all projects that would be built (best effort, applies only to sbt currently)
- **Should we create and publish reports** (default `false`) - a special option used when running community build for release versions of the compiler. If set to true it would generate a report and publish it on https://virtuslab.github.io/community-build3/. **Should be used with care, reserved for releases**

Each of the jobs would contain a generated artifact aggregating the results of the failures. 

Due to the limits of GitHub Actions responsiveness for a large number of jobs the Web UI might fail to load. In such case, you can try to refresh the page which should hopefully have enough data in the cache to return before the timeout or you can create a custom report using a dedicated job (see `Comparing results of 2 builds`)

For full coverage it's recommended to execute all available builds, e.g. `Custom Build A`, `Custom Build B`, etc, by manually starting dedicated sibling workflow jobs using the same arguments. 

When running the build it is also possible to specify the branch containing the configuration of the project. In most cases, it should be left with a default value equal to `master`. It can be overridden to a branch containing a special kind of config, eg. `historic-config/release-3.x.x-cutoff` which uses the versions of the build projects on the day of release Scala version with version `3.x.x`. Can be used to run old versions of projects, which with their latest versions might be using binary-incompatible versions of TASTy. 

### On-demand single-project builds
[Custom Project Build](https://github.com/VirtusLab/community-build3/actions/workflows/buildSingle.yaml)

Can be used to run the build for a single project using the GitHub coordinates in the format `<organization>/<repository>`, e.g. `VirtusLab/scala-cli`. It accepts the same set of additional options as `On-demand builds` described above.

### Bisect builds
[Bisect build](https://github.com/VirtusLab/community-build3/actions/workflows/buildBisect.yaml)

A workflow dedicated to running a bisect job on a given project, using the published versions of Scala compiler to find 2 last good and first failing nightly releases of Scala compiler introducing regression. Based on these the commit-based bisect is executed, which builds the compiler for every commit between these 2 releases and uses them to find commit introducing regression. Accept most of the settings defined for `Demand single-project builds` and the specific ones:
- **List of project targets to build** - can be used to build only 1 sub-project that is failing, otherwise it would build all projects
- **Specific version of project to bisect against** - if left empty the latest version (defined in the config) would be used
- **The first version of Scala versions range to test against** - to limit the minimal version of Scala accepted by the project. The project should be able to build using this version of Scala
- **The last version of Scala versions range to test against** - the maximal version of Scala, to limit the number of tested versions. 

### Comparing results of 2 builds
[Compare builds](https://github.com/VirtusLab/community-build3/actions/workflows/compare.yaml)

A workflow dedicated to comparing the results of 2 historical builds. It would query the database for results of the historical builds and would try to compare them. Accepts the following options:
- **Reference version of Scala**
- **Version of Scala to compare against** (Optional) - if empty the report would return only results of reference version.
- **Optional version of reference build id** - the buildId is defined when running the custom builds or assigned using the default value
- **Optional version of build id to compare against** - the buildId is defined when running the custom builds or assigned using the default value

