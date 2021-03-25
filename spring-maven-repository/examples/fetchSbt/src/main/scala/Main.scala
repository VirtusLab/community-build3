import scala.io.StdIn.{readLine, readInt}
@main def Main = {
  Greeter.sayHello
  
  var name = ""
  while(name != "exit"){
    name = readLine()
    println(s"Your name is $name")
  }
}
