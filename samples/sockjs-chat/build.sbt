name := "sockjs-chat"

version := "0.1"

scalaVersion := "2.12.8"

enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  guice,
  "com.github.fdimuccio" %% "play2-sockjs" % "0.7.1"
)
