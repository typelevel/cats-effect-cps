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

ThisBuild / baseVersion := "0.4"

ThisBuild / organization := "org.typelevel"
ThisBuild / organizationName := "Typelevel"

ThisBuild / startYear := Some(2021)
ThisBuild / endYear := Some(2022)

ThisBuild / homepage := Some(url("https://github.com/typelevel/cats-effect-cps"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/typelevel/cats-effect-cps"),
    "scm:git@github.com:typelevel/cats-effect-cps.git"))

ThisBuild / developers := List(
  Developer("djspiewak", "Daniel Spiewak", "@djspiewak", url("https://github.com/djspiewak")),
  Developer("baccata", "Olivier Melois", "@baccata", url("https://github.com/baccata")))

ThisBuild / crossScalaVersions := Seq("2.12.15", "2.13.8", "3.1.2")

val CatsEffectVersion = "3.3.12"

lazy val root = project.in(file(".")).aggregate(core.jvm, core.js).enablePlugins(NoPublishPlugin)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("core"))
  .settings(
    name := "cats-effect-cps",

    scalacOptions ++= {
      if (isDotty.value)
        Seq()
      else
        Seq("-Xasync")
    },

    Compile / unmanagedSourceDirectories +=
      (Compile / baseDirectory).value.getParentFile() / "shared" / "src" / "main" / s"scala-${scalaVersion.value.split('.').head}",

    Test / unmanagedSourceDirectories +=
      (Test / baseDirectory).value.getParentFile() / "shared" / "src" / "main" / s"scala-${scalaVersion.value.split('.').head}",

    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect-std" % CatsEffectVersion,

      "org.typelevel" %%% "cats-effect"                % CatsEffectVersion % Test,
      "org.typelevel" %%% "cats-effect-testing-specs2" % "1.3.0"           % Test),

    libraryDependencies ++= {
      if (isDotty.value)
        Seq("com.github.rssh" %%% "dotty-cps-async" % "0.9.8")
      else
        Seq("org.scala-lang" % "scala-reflect"   % scalaVersion.value % "provided")
    })
