package org.scalameter.japi

import java.lang.annotation.Annotation
import java.lang.reflect.Method
import org.scalameter.BasePerformanceTest._
import org.scalameter._
import org.scalameter.japi.annotation._
import org.scalameter.reporting.{HtmlReporter, RegressionReporter}
import scala.collection.mutable
import scala.language.reflectiveCalls
import scala.util.{DynamicVariable, Try}


/** Base class for all annotation based benchmarks. */
abstract class JBench extends BasePerformanceTest with Serializable {

  override final def rebuildSetupZipper() = constructSetupTree()

  /** Constructs setup tree.
   */
  private def constructSetupTree() = {
    object BenchmarkExtractor {
      def unapply(m: Method): Option[Seq[String]] = {
        Option(m.getAnnotation(classOf[benchmark])).map(_.value().split('.').toSeq)
      }
    }

    val clazz = this.getClass
    val scopedSetups: Seq[(Seq[String], Seq[Method])] = {
      val mapping = mutable.Map.empty[Seq[String], Seq[Method]]
      for (m @ BenchmarkExtractor(scopes) <- clazz.getMethods) {
        mapping.get(scopes) match {
          case Some(setups) =>
            mapping += (scopes -> (setups :+ m))
          case None =>
            mapping += (scopes -> Seq(m))
        }
      }
      mapping.toList
    }
    val scopeCtxs = {
      Option(clazz.getAnnotation(classOf[scopes])).map { a =>
        a.value().map { sc =>
          sc.scope().split('.').toSeq ->
            getFieldOrMethod(clazz, sc.context(),
              s"'scopeCtx' in the `scopes` annotation over ${clazz.getSimpleName}"
            ).asInstanceOf[Context]
        }.toMap
      }.getOrElse(Map.empty)
    }
    for ((scope, mapping) <- scopedSetups.groupBy(_._1.head)) {
      setScope(clazz, scopeCtxs, scope, mapping.map(kv => (kv._1.tail, kv._2)))
    }
  }

  /** Extends the scope named `name` with the corresponding context specified in
   *  `scopeCtxs` and adds the corresponding benchmark snippets in `scopedSetups`.
   *
   *  After this method is invoked, the setup zipper is set to a setup tree
   *  that contains all the benchmark snippets.
   */
  private def setScope(clazz: Class[_],
    scopeCtxs: Map[Seq[String], Context], name: String,
    scopedSetups: Seq[(Seq[String], Seq[Method])]): Unit = {
    val scope = Scope(name, setupzipper.value.current.context)
    scope config {
      scopeCtxs.getOrElse(
        (scope.name :: scope.context(Key.dsl.scope)).reverse, Context.empty
      )
    } in {
      scopedSetups.groupBy(_._1.headOption).foreach {
        case (None, newMapping) =>
          for ((_, ms) <- newMapping; m <- ms) {
            setSetup(clazz, m)
          }
        case (Some(newScope), newMapping) =>
          setScope(
            clazz, scopeCtxs, newScope, newMapping.map(kv => (kv._1.tail, kv._2))
          )
      }
    }
  }

  /** Sets setup for a benchmark snippet.
   */
  private def setSetup(cl: Class[_], m: Method) {
    val additionalContext = Option(m.getAnnotation(classOf[ctx])).map(a =>
      getFieldOrMethod(cl, a.value(),
        s"'ctx' annotation over '${m.getName}' method").asInstanceOf[Context]
    ).getOrElse(Context.empty)

    val gen = Option(m.getAnnotation(classOf[gen])).map(a =>
      getFieldOrMethod(cl, a.value(),
        s"'gen' annotation over '${m.getName}' method") match {
        case jgen: JGen[_] => jgen.asScala().asInstanceOf[Gen[AnyRef]]
        case gen: Gen[_] => gen.asInstanceOf[Gen[AnyRef]]
        case other => sys.error(s"Unknown generator type in '${a.value}. " +
          s"Expected JGen or Gen. Got ${other.getClass.getSimpleName}'.")
      }
    ).getOrElse(sys.error("Each benchmark method should be annotated with 'gen'."))

    val setupBeforeAll = getNoArgMethod(m, classOf[setupBeforeAll], cl)
      .asInstanceOf[Option[() => Unit]]

    val teardownAfterAll = getNoArgMethod(m, classOf[teardownAfterAll], cl)
      .asInstanceOf[Option[() => Unit]]

    val setup: Option[AnyRef => Any] = getOneArgMethod(m, classOf[setup], cl)

    val teardown: Option[AnyRef => Any] = getOneArgMethod(m, classOf[teardown], cl)

    val warmup: Option[() => Any] = getNoArgMethod(m, classOf[warmup], cl)

    val snippet: AnyRef => Any = {
      m.setAccessible(true)
      val sm = new SerializableMethod(m)
      v => sm.invokeA(this, v)
    }

    val curveName = Option(m.getAnnotation(classOf[curve]))
      .map(_.value()).getOrElse(m.getName)

    val context = setupzipper.value.current.context +
      (Key.dsl.curve -> curveName) ++ additionalContext

    val benchmark = Setup[AnyRef](
      context = context,
      gen = gen,
      setupbeforeall = setupBeforeAll,
      teardownafterall = teardownAfterAll,
      setup = setup,
      teardown = teardown,
      customwarmup = warmup,
      snippet = snippet,
      customExecutor = executor
    )

    setupzipper.value = setupzipper.value.addItem(benchmark)
  }


  /** Gets value from a field or no-arg method.
   */
  private def getFieldOrMethod(selfClass: Class[_],
    name: String, errorPrfx: String): Any = {
    Try(selfClass.getField(name).get(this)) orElse
      Try(selfClass.getMethod(name).invoke(this)) getOrElse
      sys.error(
        s"$errorPrfx is referring to a non-existent field or 0-arg method $name"
      )
  }

  /** Returns no-arg method pointed by given annotation.
   */
  private def getNoArgMethod[A <: Annotation { def value(): String }](from: Method,
    annotationClass: Class[A], selfClass: Class[_]): Option[() => Any] = {
    Option(from.getAnnotation(annotationClass)).map { a =>
      val m = selfClass.getMethod(a.value())
      m.setAccessible(true)
      val sm = new SerializableMethod(m)
      () => sm.invoke(this)
    }
  }

  /** Returns one-arg method pointed by given annotation.
    */
  private def getOneArgMethod[A <: Annotation { def value(): String }](from: Method,
    annotationClass: Class[A], selfClass: Class[_]): Option[AnyRef => Any] = {
    Option(from.getAnnotation(annotationClass)).map { a =>
      val m = selfClass.getMethods.find(_.getName == a.value())
        .getOrElse(throw new NoSuchMethodException(a.value()))
      require(m.getParameterTypes.length == 1,
        s"Expected method ${a.value()} to have single argument. " +
          s"Got ${m.getParameterTypes.length} arguments.")
      m.setAccessible(true)
      val sm = new SerializableMethod(m)
      v => sm.invokeA(this, v)
    }
  }
}

object JBench {
  /** Annotation based equivalent of the [[org.scalameter.PerformanceTest.Quickbenchmark]] */
  abstract class Quick extends JBench {
    def aggregator: Aggregator = Aggregator.min

    def measurer: Measurer = new Measurer.Default()

    def persistor: Persistor = Persistor.None

    def reporter: Reporter = new reporting.LoggingReporter

    def warmer: Warmer = Warmer.Default()

    final def executor: Executor = execution.LocalExecutor(
      warmer,
      aggregator,
      measurer
    )
  }

  /** Annotation based equivalent of the [[org.scalameter.PerformanceTest.Microbenchmark]] */
  abstract class Micro extends JBench {
    def aggregator: Aggregator = Aggregator.min

    def measurer: Measurer =
      new Measurer.IgnoringGC with Measurer.PeriodicReinstantiation {
        override val defaultFrequency = 12
        override val defaultFullGC = true
      }

    def persistor: Persistor = Persistor.None

    def reporter: Reporter = new reporting.LoggingReporter

    def warmer: Warmer = Warmer.Default()

    final def executor: Executor = execution.SeparateJvmsExecutor(
      warmer,
      aggregator,
      measurer
    )
  }

  /** Annotation base equivalent of the [[org.scalameter.PerformanceTest.HTMLReport]] */
  abstract class HTMLReport extends JBench {
    def aggregator: Aggregator = Aggregator.average

    def executor: Executor = new execution.SeparateJvmsExecutor(
      warmer,
      aggregator,
      measurer
    )

    def measurer: Measurer =
      new Measurer.IgnoringGC with Measurer.PeriodicReinstantiation
        with Measurer.OutlierElimination with Measurer.RelativeNoise

    def persistor: Persistor = new persistence.GZIPJSONSerializationPersistor

    def reporter: Reporter = Reporter.Composite(
      new RegressionReporter(tester, historian),
      HtmlReporter(!online)
    )

    def warmer: Warmer = Warmer.Default()

    def historian: RegressionReporter.Historian

    def online: Boolean

    def tester: RegressionReporter.Tester
  }

  /** Annotation base equivalent of the [[org.scalameter.PerformanceTest.OnlineRegressionReport]] */
  abstract class OnlineRegressionReport extends HTMLReport {
    def historian: RegressionReporter.Historian =
      RegressionReporter.Historian.ExponentialBackoff()

    def online = true

    def tester: RegressionReporter.Tester =
      RegressionReporter.Tester.OverlapIntervals()
  }

  /** Annotation base equivalent of the [[org.scalameter.PerformanceTest.OfflineRegressionReport]] */
  abstract class OfflineRegressionReport extends HTMLReport {
    def historian: RegressionReporter.Historian =
      RegressionReporter.Historian.ExponentialBackoff()

    def online = false

    def tester: RegressionReporter.Tester =
      RegressionReporter.Tester.OverlapIntervals()
  }

  /** Annotation base equivalent of the [[org.scalameter.PerformanceTest.OfflineReport]] */
  abstract class OfflineReport extends HTMLReport {
    def historian: RegressionReporter.Historian =
      RegressionReporter.Historian.ExponentialBackoff()

    def online = false

    def tester: RegressionReporter.Tester =
      RegressionReporter.Tester.Accepter()
  }
}
