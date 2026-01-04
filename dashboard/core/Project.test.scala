package dashboard.core

import munit.FunSuite

class ProjectNameTest extends FunSuite:

  test("parse valid org/repo format"):
    assert(ProjectName("typelevel/cats").isRight)
    assert(ProjectName("scala/scala3").isRight)

  test("convert legacy org_repo format to org/repo"):
    val result = ProjectName("typelevel_cats")
    assert(result.isRight)
    assertEquals(result.map(_.searchName), Right("typelevel/cats"))

  test("reject invalid formats"):
    assert(ProjectName("invalid").isLeft)
    assert(ProjectName("").isLeft)
    assert(ProjectName("/repo").isLeft)
    assert(ProjectName("org/").isLeft)

  test("extract org and repo"):
    val name = ProjectName.unsafeApply("typelevel/cats")
    assertEquals(name.org, "typelevel")
    assertEquals(name.repo, "cats")

  test("provide search names"):
    val name = ProjectName.unsafeApply("typelevel/cats")
    assertEquals(name.searchName, "typelevel/cats")
    assertEquals(name.legacySearchName, "typelevel_cats")

class SemVersionTest extends FunSuite:

  test("parse valid versions"):
    assert(SemVersion("3.3.0").isDefined)
    assert(SemVersion("3.3.0-RC1").isDefined)
    assert(SemVersion("3.3.0-RC1-bin-20240101-hash-NIGHTLY").isDefined)

  test("reject invalid versions"):
    assert(SemVersion("invalid").isEmpty)
    assert(SemVersion("3").isEmpty)
    assert(SemVersion("3.3").isEmpty)

  test("compare versions correctly"):
    val v330 = SemVersion.unsafeApply("3.3.0")
    val v331 = SemVersion.unsafeApply("3.3.1")
    val v340 = SemVersion.unsafeApply("3.4.0")

    assert(v331.isNewerThan(v330))
    assert(v340.isNewerThan(v331))
    assert(!v330.isNewerThan(v331))

  test("stable version is newer than RC"):
    val stable = SemVersion.unsafeApply("3.3.0")
    val rc = SemVersion.unsafeApply("3.3.0-RC1")

    given Ordering[SemVersion] = SemVersion.given_Ordering_SemVersion
    assert(Ordering[SemVersion].compare(stable, rc) > 0)

  test("isNewerOrEqualTo includes equal versions"):
    val v330 = SemVersion.unsafeApply("3.3.0")
    assert(v330.isNewerOrEqualTo(v330))
