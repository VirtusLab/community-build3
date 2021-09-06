object Fail {
  def fail: Unit = {
    val s: String = 1 // this should not compile
  }
}
