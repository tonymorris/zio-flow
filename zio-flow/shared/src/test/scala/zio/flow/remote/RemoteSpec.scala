package zio.flow.remote

import zio.{ZIO, ZLayer}
import zio.flow._
import zio.flow.remote.numeric._
import zio.flow.serialization.RemoteSerializationSpec.TestCaseClass
import zio.schema.{DynamicValue, Schema, StandardType}
import zio.test.Assertion._
import zio.test._

object RemoteSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment, Any] =
    suite("Remote")(
      suite("Literal")(
        test("evaluates correctly") {
          val remote = Remote("test")
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[String]
            } yield assertTrue(
              dyn == DynamicValue.Primitive("test", StandardType[String]),
              typ == "test"
            )

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        }
      ),
      suite("Variable")(
        test("evaluates to the stored value") {
          val name   = RemoteVariableName("test")
          val remote = Remote.Variable(name)
          val test =
            for {
              _   <- RemoteContext.setVariable(name, DynamicValue.fromSchemaAndValue(Schema[String], "test"))
              dyn <- remote.evalDynamic
              typ <- remote.eval[String]
            } yield assertTrue(
              dyn == DynamicValue.Primitive("test", StandardType[String]),
              typ == "test"
            )

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        },
        test("fails if not stored") {
          val name   = RemoteVariableName("test")
          val remote = Remote.Variable(name)
          val test =
            for {
              dyn <- remote.evalDynamic.exit
            } yield assert(dyn)(fails(equalTo("Could not find identifier test")))

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        }
      ),
      suite("Either")(
        test("evaluates correctly when it is Left") {
          val remote = Remote.RemoteEither(Left(Remote("test")))
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Either[String, Int]]
            } yield assertTrue(
              dyn == DynamicValue.fromSchemaAndValue(Schema.either[String, Int], Left("test")),
              typ == Left("test")
            )

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        },
        test("evaluates correctly when it is Right") {
          val remote = Remote.RemoteEither(Right(Remote("test")))
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Either[Int, String]]
            } yield assertTrue(
              dyn == DynamicValue.fromSchemaAndValue(Schema.either[Int, String], Right("test")),
              typ == Right("test")
            )

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        }
      ),
      suite("SwapEither")(
        test("evaluates correctly when it is Left") {
          val remote =
            Remote.SwapEither(Remote.RemoteEither(Left(Remote("test"))))
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Either[Int, String]]
            } yield assertTrue(
              dyn == DynamicValue.fromSchemaAndValue(Schema.either[Int, String], Right("test")),
              typ == Right("test")
            )

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        },
        test("evaluates correctly when it is Right") {
          val remote =
            Remote.SwapEither(Remote.RemoteEither(Right(Remote("test"))))
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Either[String, Int]]
            } yield assertTrue(
              dyn == DynamicValue.fromSchemaAndValue(Schema.either[String, Int], Left("test")),
              typ == Left("test")
            )

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        }
      ),
      suite("Tuple3")(
        test("evaluates correctly") {
          val remote = Remote.Tuple3(
            Remote("hello"),
            Remote(TestCaseClass("x", 2)),
            Remote(1)
          )
          val expectedValue = ("hello", TestCaseClass("x", 2), 1)
          val expectedDynamicValue =
            DynamicValue.fromSchemaAndValue(Schema.tuple3[String, TestCaseClass, Int], expectedValue)
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[(String, TestCaseClass, Int)]
            } yield assertTrue(
              dyn == expectedDynamicValue,
              typ == expectedValue
            )

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        }
      ),
      suite("LessThanEqual")(
        test("evaluates correctly when less") {
          val remote = Remote.LessThanEqual(Remote(5), Remote(10), Schema[Int])
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Boolean]
            } yield assertTrue(
              dyn == DynamicValue.fromSchemaAndValue(Schema[Boolean], true),
              typ == true
            )

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        },
        test("evaluates correctly when greater") {
          val remote = Remote.LessThanEqual(Remote(50), Remote(10), Schema[Int])
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Boolean]
            } yield assertTrue(
              dyn == DynamicValue.fromSchemaAndValue(Schema[Boolean], false),
              typ == false
            )

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        }
      ),
      suite("Fold")(
        test("evaluates correctly") {
          val remote = Remote.Fold(
            Remote(List(10, 20, 30)),
            Remote(100),
            Remote.EvaluatedRemoteFunction.make((tuple: Remote[(Int, Int)]) =>
              Remote.BinaryNumeric(
                Remote.TupleAccess(tuple, 0),
                Remote.TupleAccess(tuple, 1),
                Numeric.NumericInt,
                BinaryNumericOperator.Add
              )
            )
          )
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[Int]
            } yield assertTrue(
              dyn == DynamicValue.fromSchemaAndValue(Schema[Int], 160),
              typ == 160
            )

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        },
        test("evaluates correctly with custom types") {
          val remote = Remote.Fold(
            Remote(List(TestCaseClass("a", 10), TestCaseClass("b", 20), TestCaseClass("c", 30))),
            Remote(TestCaseClass("d", 40)),
            Remote.EvaluatedRemoteFunction.make((tuple: Remote[(TestCaseClass, TestCaseClass)]) =>
              Remote.TupleAccess[(TestCaseClass, TestCaseClass), TestCaseClass](tuple, 1)
            )
          )
          val test =
            for {
              dyn <- remote.evalDynamic
              typ <- remote.eval[TestCaseClass]
            } yield assertTrue(
              dyn == DynamicValue.fromSchemaAndValue(Schema[TestCaseClass], TestCaseClass("c", 30)),
              typ == TestCaseClass("c", 30)
            )

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        },
        test("folded flow") {
          val remote = Remote(List(1, 2))
            .fold(ZFlow.succeed(0)) { case (flow, n) =>
              flow.flatMap { prevFlow =>
                ZFlow.unwrap(prevFlow).map(_ + n)
              }
            }

          val test =
            for {
              _ <- remote.evalDynamic
            } yield assertCompletes

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        }
      ),
      suite("Flow")(
        test("flow containing schema of flow") {
          val flow = ZFlow.succeed(ZFlow.succeed(1))
          val test = for {
            dyn <- Remote.Flow(flow).evalDynamic
            typ <- ZIO.fromEither(dyn.toTypedValue(ZFlow.schemaAny))
          } yield assertTrue(typ == ZFlow.succeed(ZFlow.succeed(1)))

          test.provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
        }
      )
    )
}
