/*
 * Copyright 2021 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

name := "cats-effect-cps"

ThisBuild / baseVersion := "0.1"

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"

ThisBuild / homepage := Some(url("https://github.com/typelevel/cats-effect-cps"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/typelevel/cats-effect-cps"),
    "scm:git@github.com:typelevel/cats-effect-cps.git"))

ThisBuild / developers := List(
  Developer("djspiewak", "Daniel Spiewak", "@djspiewak", url("https://github.com/djspiewak")),
  Developer("baccata", "Olivier Melois", "@baccata", url("https://github.com/baccata")))

ThisBuild / crossScalaVersions := Seq("2.12.14", "2.13.6")

val CatsEffectVersion = "3.1.0"

lazy val root = project.in(file(".")).aggregate(core.jvm, core.js).enablePlugins(NoPublishPlugin)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("core"))
  .settings(
    name := "cats-effect-cps",

    scalacOptions += "-Xasync",

    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect-std" % CatsEffectVersion,
      "org.scala-lang" % "scala-reflect"   % scalaVersion.value % "provided",

      "org.typelevel" %% "cats-effect"                % CatsEffectVersion % Test,
      "org.typelevel" %% "cats-effect-testing-specs2" % "1.1.1"           % Test,
      "org.specs2"    %% "specs2-core"                % "4.12.1"          % Test))
