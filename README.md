# Scala 3 Open Community Build

It's a project dedicated to test new versions of the compiler against the existing Scala 3 ecosystem. 

## Including projects in Open Community Build

The Open Community Build consists of automated gathering information about available Scala 3 projects, using the information avaiable from Scaladex and Maven repositories. Every project listed in Scaladex would be included in the candidates for the builds. The not yet published libraries or non-library open-source projects can be added to [custom projects list](
https://github.com/VirtusLab/community-build3/blob/master/coordinator/configs/custom-projects.txt) using the GitHub coordinates.

Some of the projects might be filtered-out if they're non compliant, eg. they have build issues, require special environment or don't compile with any recent version of Scala for long time (are unmaintained). Such projects are [listed in dedicated file](
https://github.com/VirtusLab/community-build3/blob/master/coordinator/configs/filtered-projects.txt) The project might be removed from this list and reintroduced to the weekly builds when it becomes compliant again, which in most cases involves fixing build issues.

Each project might define it's config (HOCON) describing how should we handle it, eg. which JDK version to use, to exclude some legacy modules, add some sbt options, or disable compilation/running tests if they're buggy/flaky. It can be defined in projects top-level file using file named ``scala3-community-build.conf` or in our [internal config](https://github.com/VirtusLab/community-build3/blob/master/coordinator/configs/projects-config.conf)

Based on all of these we create a build plan enlisting projects which we should run, and their final config used when compiling them. We do this each automatically every Friday few hours before running the weekly build.

The final configuration of all the projects can be found in dedicated file [buildConfig.json](https://github.com/VirtusLab/community-build3/blob/master/.github/workflows/buildConfig.json) 



## Executing community build
Execution of the community build is handled by the GitHub Actions CI using dedicated jobs. Due to the limitations of the GitHub actions a single job can handle up to 1000 community projects, to test more projects we split them into multiple jobs with with a suffix in the form of single subsequent alphabet character. Based on the popularity of projects expressed in ammount of GitHub stars first 1000 projects are included in community build A, next 1000 projects in build B, etc. 

The list of all included projects and their order of executing can be found in automatically updated build plans: 
- [Build Plan A](https://github.com/VirtusLab/community-build3/blob/master/.github/workflows/buildPlan-A.yaml)
- [Build Plan B](https://github.com/VirtusLab/community-build3/blob/master/.github/workflows/buildPlan-B.yaml)


---
**The execution of the workflow jobs requires explicit access**, these can be granted by the VirtusLab compiler team. If you don't have access to running GitHub Actions with the jobs listed below contact @WojciechMazur

---
 
### Scheduled 
[Scheduled Build A](https://github.com/VirtusLab/community-build3/actions/workflows/buildExecuteScheduledWeekly-A.yaml)
[Scheduled Build B](https://github.com/VirtusLab/community-build3/actions/workflows/buildExecuteScheduledWeekly-A.yaml)

The Open Community Build is executed weekly at friday night using the latest available nightly version. The results of the builds can be found in https://virtuslab.github.io/community-build3/

### On demand builds
[Custom Build A](https://github.com/VirtusLab/community-build3/actions/workflows/buildExecuteCustom-A.yaml)
[Custom Build B](https://github.com/VirtusLab/community-build3/actions/workflows/buildExecuteCustom-B.yaml)

The compiler team might be intrested in executing on demand builds with a configured version of Scala to use. For that we can use a dedicated CI workflow run taking a set of arguments:
- **Custom name of the job** (Optional) - a custom string allowing to identified results of the job, would be also used as a buildId when persisting the results in the database. 
- **Published Scala version to use** (Optional) - if non empty the value would be used to determinate an already published version to use, otherwise if left empty, the compiler would be build using the next 2 options.
- **GitHub repository for compiler** (defaults to `lampepfl/dotty`) - when building the compiler this repository would be used to provider the compiler sources
- **GitHub repository branch** (defaults to `main`) - when building the compiler this value would point to the branch in the repository provided in the previous setting, that should be used to build the compiler in the
- **List of scalacOptions to include** (Optional) - comma delimiated list of Scala compiler options to apply for all projects that would be build (best effort, applies only to sbt and scala-cli currently)
- **List of scalacOptions to exclude** (Optional) - comma delimiated list of Scala compiler options to remove for all projects that would be build (best effort, applies only to sbt currently)
- **Should we create and publish raport** (default `false`) - a special option used when running community build for release versions of compiler. Is set to true it would generate a raport and publish it on https://virtuslab.github.io/community-build3/. **Should be used with care, reserved for releases**

Each of the jobs would contain a generated artifact aggregating the results about the failures. 

Due to limits of GitHub Actions responsivnes for large ammount of jobs the Web UI might fail to load. In such case you can try to refresh the page which should hopefully have enough data in cache to return before the timeout or you can create a custom report using a dedicated job (see `Comparing results of 2 builds`)

For the full coverage it's recommended to execute all available builds, e.g. `Custom Build A`, `Custom Build B`, etc, by manually starting dedicated sibling workflow jobs using the same arguments. 

When running the build it is also possible to specify the branch containing the projects configuration. In most cases it should be left with default value equal to `master`. It can be overriden to branch containg special kind of config, eg. `historic-config/release-3.x.x-cutoff` which uses the versions of the build projects at the day of release Scala version with versin `3.x.x`. Can be used to run old versions of projects, which with their latest versions might be using binary-incompatbile versions of TASTy. 

### On demand single-project builds
[Custom Project Build](https://github.com/VirtusLab/community-build3/actions/workflows/buildSingle.yaml)

Can be used to run the build for a single project using the GitHub coordinates in the format `<organization>/<repository>`, e.g. `VirtusLab/scala-cli`. It accepts the same set of additional options as `On demand builds` described above.

### Bisect builds
[Bisect build](https://github.com/VirtusLab/community-build3/actions/workflows/buildBisect.yaml)

A workflow dedicated to running a bisect job on a given project, using the published versions of Scala compiler to find 2 last good and first failing nightly release of Scala compiler introducing regression. Based on these the commit based bisect is executed, which builds the compiler for every commit between these 2 releases and used them to find commit introducing regression. Accept most of settings defined for `On demand single-project builds` and the specific ones:
- **List of project targets to builds** - can be used to build only 1 sub-project which is failing, otherwise it would build all projects
- **Specific version of project to bisect against** - if left empty the latest version (defined in the config) would be used
- **The first version of Scala versions range to test against** - to limit minimal version of Scala accepted by project. The project should be able to build using this version of Scala
- **The last version of Scala versions range to test against** - the maximal version of Scala, to limit the ammount of tested version. 

### Comparing results of 2 builds
[Compare builds](https://github.com/VirtusLab/community-build3/actions/workflows/compare.yaml)
A workflow dedicated to compare results of 2 historical builds. It would query the database for results of the historical builds and would try to compare them. Accepts following options:
- **Reference version of Scala**
- **Version of Scala to compare against** (Optional) - if empty the report would return only results of reference version.
- **Optional version of reference build id** - the buildId is defined when running the custom builds or assigned using the default value
- **Optional version of build id to compare against** - the buildId is defined when running the custom builds or assigned using the default value

