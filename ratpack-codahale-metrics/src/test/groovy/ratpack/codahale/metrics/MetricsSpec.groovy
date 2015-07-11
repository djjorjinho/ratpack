/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.codahale.metrics

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.MetricRegistryListener
import com.codahale.metrics.SharedMetricRegistries
import com.codahale.metrics.annotation.Gauge
import com.codahale.metrics.annotation.Metered
import com.codahale.metrics.annotation.Timed
import com.codahale.metrics.graphite.GraphiteSender
import groovy.json.JsonSlurper
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import ratpack.test.internal.RatpackGroovyDslSpec
import ratpack.websocket.RecordingWebSocketClient
import spock.util.concurrent.PollingConditions

import java.time.Duration
import java.util.concurrent.TimeUnit

class MetricsSpec extends RatpackGroovyDslSpec {

  @SuppressWarnings("GroovyUnusedDeclaration")
  PollingConditions polling = new PollingConditions()

  @Rule
  TemporaryFolder reportDirectory

  def setup() {
    SharedMetricRegistries.clear()
  }

  def "can register metrics module"() {
    when:
    bindings {
      module new CodaHaleMetricsModule(), { }
    }
    handlers { MetricRegistry metrics ->
      all {
        render metrics.getClass().name
      }
    }

    then:
    text == "com.codahale.metrics.MetricRegistry"
  }

  def "can register reporters"() {
    given:
    List<MetricRegistryListener> listeners = null
    def output = new ByteArrayOutputStream()

    def origOut = System.out
    System.out = new PrintStream(output, true)

    def graphite = Mock(GraphiteSender) {
      send(_,_,_) >> {args -> println(args)}
      isConnected() >> true
      getFailures() >> 0
    }

    and:
    bindings {
      module new CodaHaleMetricsModule(), { it
        .jmx()
        .csv { it.reportDirectory(reportDirectory.root).reporterInterval(Duration.ofSeconds(1)) }
        .console { it.reporterInterval(Duration.ofSeconds(1)) }
        .graphite { it.sender(graphite).prefix("graphite").reporterInterval(Duration.ofSeconds(1)) }
      }
    }
    handlers { MetricRegistry metrics ->
      all {
        //noinspection GroovyAccessibility
        listeners = metrics.listeners
        render "ok"
      }
    }

    when:
    text

    then:
    listeners.size() == 1 // JMX listener
    polling.within(2) {
      reportDirectory.root.listFiles().length > 0
    }
    polling.within(2) {
      output.toString().contains("root.get-requests") &&
      output.toString().contains("graphite.root.get-requests")
    }

    cleanup:
    System.out = origOut
  }

  def "can collect custom metrics"() {
    def reporter = Mock(MetricRegistryListener)
    def requestMeter

    given:
    bindings {
      module new CodaHaleMetricsModule(), {}
    }

    handlers { MetricRegistry metrics ->
      // TODO this is a bad place to do this - We should auto register user added MetricRegistryListeners
      // Also need to consider a more general post startup hook
      metrics.addListener(reporter)

      all {
        metrics.meter("requests").mark()
        render ""
      }
    }

    when:
    2.times { get() }

    then:
    1 * reporter.onMeterAdded("requests", !null) >> { arguments ->
      requestMeter = arguments[1]
    }
    requestMeter.count == 2
  }

  @com.google.inject.Singleton
  static class AnnotatedMetricService {

    @Metered(name = 'foo meter', absolute = true)
    public AnnotatedMetricService triggerMeter1() { this }

    @Metered(name = 'foo meter')
    public AnnotatedMetricService triggerMeter2() { this }

    @Metered
    public AnnotatedMetricService triggerMeter3() { this }

    @Gauge(name = 'foo gauge', absolute = true)
    public String triggerGauge1() { "gauge1" }

    @Gauge(name = 'foo gauge')
    public String triggerGauge2() { "gauge2" }

    @Gauge
    public String triggerGauge3() { "gauge3" }

    @Timed(name = 'foo timer', absolute = true)
    public AnnotatedMetricService triggerTimer1() { this }

    @Timed(name = 'foo timer')
    public AnnotatedMetricService triggerTimer2() { this }

    @Timed
    public AnnotatedMetricService triggerTimer3() { this }

  }

  def "can collect metered annotated metrics"() {
    def reporter = Mock(MetricRegistryListener)
    def absoluteNamedMeter
    def namedMeter
    def unNamedMeter

    given:
    bindings {
      module new CodaHaleMetricsModule(), {}
      bind AnnotatedMetricService
    }

    handlers { MetricRegistry metrics ->
      metrics.addListener(reporter)

      path("meter") { AnnotatedMetricService service ->
        service
          .triggerMeter1()
          .triggerMeter2()
          .triggerMeter3()
          .triggerMeter1()

        render ""
      }
    }

    when:
    2.times { get("meter") }

    then:
    1 * reporter.onMeterAdded("foo meter", !null) >> { arguments ->
      absoluteNamedMeter = arguments[1]
    }

    1 * reporter.onMeterAdded('ratpack.codahale.metrics.MetricsSpec$AnnotatedMetricService.triggerMeter3', !null) >> { arguments ->
      namedMeter = arguments[1]
    }

    1 * reporter.onMeterAdded('ratpack.codahale.metrics.MetricsSpec$AnnotatedMetricService.foo meter', !null) >> { arguments ->
      unNamedMeter = arguments[1]
    }

    absoluteNamedMeter.count == 4
    namedMeter.count == 2
    unNamedMeter.count == 2
  }

  def "can collect timed annotated metrics"() {
    def reporter = Mock(MetricRegistryListener)
    def absoluteNamedTimer
    def namedTimer
    def unNamedTimer

    given:
    bindings {
      module new CodaHaleMetricsModule(), {}
      bind AnnotatedMetricService
    }

    handlers { MetricRegistry metrics ->
      metrics.addListener(reporter)

      path("timer") { AnnotatedMetricService service ->
        service
          .triggerTimer1()
          .triggerTimer2()
          .triggerTimer3()
          .triggerTimer1()

        render ""
      }
    }

    when:
    2.times { get("timer") }

    then:
    1 * reporter.onTimerAdded("foo timer", !null) >> { arguments ->
      absoluteNamedTimer = arguments[1]
    }

    1 * reporter.onTimerAdded('ratpack.codahale.metrics.MetricsSpec$AnnotatedMetricService.triggerTimer3', !null) >> { arguments ->
      namedTimer = arguments[1]
    }

    1 * reporter.onTimerAdded('ratpack.codahale.metrics.MetricsSpec$AnnotatedMetricService.foo timer', !null) >> { arguments ->
      unNamedTimer = arguments[1]
    }

    absoluteNamedTimer.count == 4
    namedTimer.count == 2
    unNamedTimer.count == 2
  }

  def "can collect gauge annotated metrics"() {
    MetricRegistry registry

    given:
    bindings {
      module new CodaHaleMetricsModule(), {}
      bind AnnotatedMetricService
    }

    handlers { MetricRegistry metrics ->
      registry = metrics

      path("gauge") { AnnotatedMetricService service ->
        render ""
      }
    }

    when:
    get("gauge")

    then:
    "gauge2" == registry.gauges.get('ratpack.codahale.metrics.MetricsSpec$AnnotatedMetricService.foo gauge').value
    "gauge3" == registry.gauges.get('ratpack.codahale.metrics.MetricsSpec$AnnotatedMetricService.triggerGauge3').value
    "gauge1" == registry.gauges.get('foo gauge').value
  }

  def "can collect request timer metrics"() {
    def reporter = Mock(MetricRegistryListener)

    given:
    bindings {
      module new CodaHaleMetricsModule(), { it.jmx() }
    }

    handlers { MetricRegistry metrics ->

      metrics.addListener(reporter)

      all {
        render ""
      }
      prefix("foo") {
        path("bar") {
          render ""
        }
        all {
          render ""
        }
      }
    }

    when:
    2.times { get() }
    2.times { get("foo") }
    2.times { get("foo/bar") }

    then:
    1 * reporter.onTimerAdded("root.get-requests", !null)
    1 * reporter.onTimerAdded("foo.get-requests", !null)
    1 * reporter.onTimerAdded("foo.bar.get-requests", !null)
  }

  def "can collect jvm metrics"() {
    def reporter = Mock(MetricRegistryListener)

    given:
    bindings {
      module new CodaHaleMetricsModule(), { it.jvmMetrics(true) }
    }

    handlers { MetricRegistry metrics ->
      metrics.addListener(reporter)

      all {
        render ""
      }
    }

    when:
    get()

    then:
    (1.._) * reporter.onGaugeAdded(!null, { it.class.name.startsWith("com.codahale.metrics.jvm.GarbageCollectorMetricSet") })
    (1.._) * reporter.onGaugeAdded(!null, { it.class.name.startsWith("com.codahale.metrics.jvm.ThreadStatesGaugeSet") })
    (1.._) * reporter.onGaugeAdded(!null, { it.class.name.startsWith("com.codahale.metrics.jvm.MemoryUsageGaugeSet") })
  }

  def "can use metrics endpoint"() {
    given:
    bindings {
      module new CodaHaleMetricsModule(), { it.webSocket { it.reporterInterval(Duration.ofSeconds(1)).excludeFilter("2xx-responses") } }
    }
    handlers { MetricRegistry metrics ->

      metrics.register("fooGauge", new com.codahale.metrics.Gauge<Integer>() {
        @Override
        public Integer getValue() {
          2
        }
      })

      get {
        metrics.meter("fooMeter").mark()
        metrics.counter("fooCounter").inc()
        metrics.histogram("fooHistogram").update(metrics.counter("fooCounter").count)
        render "foo"
      }

      get("admin/metrics-report", new MetricsWebsocketBroadcastHandler())
    }

    and:
    2.times { getText() }

    when:
    def client = openWsClient()
    client.connectBlocking()

    then:
    with(new JsonSlurper().parseText(client.received.poll(2, TimeUnit.SECONDS))) {
      timers.size() == 2
      timers.containsKey("admin.metrics-report.get-requests")
      timers["admin.metrics-report.get-requests"].count == 0
      timers.containsKey("root.get-requests")
      timers["root.get-requests"].count == 2

      gauges.size() == 1
      gauges.containsKey("fooGauge")
      gauges["fooGauge"].value == 2

      meters.size() == 1
      meters.containsKey("fooMeter")
      meters["fooMeter"].count == 2

      counters.size() == 1
      counters.containsKey("fooCounter")
      counters["fooCounter"].count == 2

      histograms.size() == 1
      histograms.containsKey("fooHistogram")
      histograms["fooHistogram"].count == 2
    }

    when:
    2.times { getText() }

    then:
    with(new JsonSlurper().parseText(client.received.poll(2, TimeUnit.SECONDS))) {
      timers.size() == 2
      timers.containsKey("admin.metrics-report.get-requests")
      timers["admin.metrics-report.get-requests"].count == 0
      timers.containsKey("root.get-requests")
      timers["root.get-requests"].count == 4

      gauges.size() == 1
      gauges.containsKey("fooGauge")
      gauges["fooGauge"].value == 2

      meters.size() == 1
      meters.containsKey("fooMeter")
      meters["fooMeter"].count == 4

      counters.size() == 1
      counters.containsKey("fooCounter")
      counters["fooCounter"].count == 4

      histograms.size() == 1
      histograms.containsKey("fooHistogram")
      histograms["fooHistogram"].count == 4
    }

    cleanup:
    client?.closeBlocking()
  }

  def RecordingWebSocketClient openWsClient() {
    new RecordingWebSocketClient(new URI("ws://localhost:$server.bindPort/admin/metrics-report"))
  }

  def "can collect blocking metrics"() {
    def reporter = Mock(MetricRegistryListener)
    def blockingTimer

    given:
    bindings {
      module new CodaHaleMetricsModule(), {}
    }

    handlers {MetricRegistry metrics ->
      metrics.addListener(reporter)

      path("foo") {
        blocking {
          2
        } then {
          render ""
        }
      }
    }

    when:
    2.times { get("foo") }

    then:
    1 * reporter.onTimerAdded("foo.get-blocking", !null) >> { arguments ->
      blockingTimer = arguments[1]
    }
    blockingTimer.count == 2
  }

  def "can filter reporters"() {
    given:
    def output = new ByteArrayOutputStream()

    def origOut = System.out
    System.out = new PrintStream(output, true)

    def graphite = Mock(GraphiteSender) {
      send(_,_,_) >> {args -> println(args)}
      isConnected() >> true
      getFailures() >> 0
    }

    and:
    bindings {
      module new CodaHaleMetricsModule(), {
        it.console { it.reporterInterval(Duration.ofSeconds(1)).includeFilter(".*ar.*").excludeFilter(".*bar.*") }
        it.jmx { it.includeFilter(".*ar.*") }
        it.csv { it.reportDirectory(reportDirectory.root).reporterInterval(Duration.ofSeconds(1)).includeFilter(".*foo.*") }
        it.graphite {it.sender(graphite).prefix("graphite").reporterInterval(Duration.ofSeconds(1)).includeFilter(".*ar.*").excludeFilter(".*bar.*")}
      }
    }

    handlers { MetricRegistry metrics ->
      all { render "" }
    }

    when:
    get("foo")
    get("bar")
    get("tar")

    then:
    polling.within(2) {
      output.toString().contains("tar.get-requests") &&
      output.toString().contains("graphite.tar.get-requests")
    }

    and:
    !output.toString().contains("foo.get-requests")
    !output.toString().contains("bar.get-requests")
    !output.toString().contains("graphite.foo.get-requests")
    !output.toString().contains("graphite.bar.get-requests")

    and:
    reportDirectory.root.listFiles().length == 1
    reportDirectory.root.listFiles()[0].name.contains("foo")

    cleanup:
    System.out = origOut
  }

  def "can apply custom groups for request timer metrics"() {
    def reporter = Mock(MetricRegistryListener)

    given:
    bindings {
      module new CodaHaleMetricsModule(), {
        it.requestMetricGroups(["bar":"bar/.*", "foo":"foo/.*", "f":"f.*"])
      }
    }

    handlers { MetricRegistry metrics ->
      metrics.addListener(reporter)
      all { render "" }
    }

    when:
    get("foo/1")
    get("foo/2/tar")
    get("far")
    get("foo/3/bar")
    get("tar?id=3")

    then:
    1 * reporter.onTimerAdded("foo.get-requests", !null)
    1 * reporter.onTimerAdded("f.get-requests", !null)
    1 * reporter.onTimerAdded("tar.get-requests", !null)
  }

  def "can collect status code metrics"() {
    def reporter = Mock(MetricRegistryListener)
    def twoxxCounter
    def fourxxCounter

    given:
    bindings {
      module new CodaHaleMetricsModule(), {}
    }

    handlers { MetricRegistry metrics ->
      metrics.addListener(reporter)
      path("foo") { render "" }
      path("bar") {
        clientError(401)
      }
    }

    when:
    get("foo")
    get("bar")
    get("tar")

    then:
    1 * reporter.onCounterAdded("2xx-responses", !null) >> { arguments ->
      twoxxCounter = arguments[1]
    }
    1 * reporter.onCounterAdded("4xx-responses", !null) >> { arguments ->
      fourxxCounter = arguments[1]
    }
    twoxxCounter.count == 1
    fourxxCounter.count == 2
  }
}
