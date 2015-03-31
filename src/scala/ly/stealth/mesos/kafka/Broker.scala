/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ly.stealth.mesos.kafka

import java.util
import scala.collection.JavaConversions._
import scala.util.parsing.json.JSONObject
import scala.collection
import org.apache.mesos.Protos.{Resource, Offer}
import java.util.{Collections, Date, UUID}
import ly.stealth.mesos.kafka.Broker.Failover
import Util.{Period, Str}

class Broker(_id: String = "0") {
  var id: String = _id
  @volatile var active: Boolean = false

  var host: Constraint = null
  var cpus: Double = 0.5
  var mem: Long = 128
  var heap: Long = 128

  var constraints: util.Map[String, Constraint] = new util.LinkedHashMap()
  var options: util.Map[String, String] = new util.LinkedHashMap()

  var failover: Failover = new Failover()

  def effectiveOptions(overrides: util.Map[String, String] = null): util.Map[String, String] = {
    val result = new util.LinkedHashMap[String, String](options)

    for ((k, v) <- result)
      result.put(k, v.replace("$id", id))

    if (overrides != null) result.putAll(overrides)

    if (!result.containsKey("log.dirs"))
      result.put("log.dirs", "kafka-logs")

    result
  }

  @volatile var task: Broker.Task = null

  def matches(offer: Offer, otherAttributes: Broker.OtherAttributes = Broker.NoAttributes): Boolean = {
    if (host != null && !host.matches(offer.getHostname, otherAttributes("host"))) return false

    // check resources
    val offerResources = new util.HashMap[String, Resource]()
    for (resource <- offer.getResourcesList) offerResources.put(resource.getName, resource)

    val cpusResource = offerResources.get("cpus")
    if (cpusResource == null || cpusResource.getScalar.getValue < cpus) return false

    val memResource = offerResources.get("mem")
    if (memResource == null || memResource.getScalar.getValue < mem) return false

    // check attributes
    val offerAttributes = new util.HashMap[String, String]()
    for (attribute <- offer.getAttributesList)
      if (attribute.hasText) offerAttributes.put(attribute.getName, attribute.getText.getValue)

    for ((name, constraint) <- constraints) {
      if (!offerAttributes.containsKey(name)) return false
      if (!constraint.matches(offerAttributes.get(name), otherAttributes(name))) return false
    }

    true
  }

  def shouldStart(offer: Offer, otherAttributes: Broker.OtherAttributes = Broker.NoAttributes, now: Date = new Date()): Boolean = {
    active && task == null && matches(offer, otherAttributes) && !failover.isWaitingDelay(now)
  }

  def shouldStop: Boolean = !active

  def state(now: Date = new Date()): String = {
    if (active) {
      if (task != null && task.running) return "running"

      if (failover.isWaitingDelay(now)) {
        var s = "failed " + failover.failures
        if (failover.maxTries != null) s += "/" + failover.maxTries
        s += " " + Str.dateTime(failover.failureTime)
        s += ", next start " + Str.dateTime(failover.delayExpires)
        return s
      }

      if (failover.failures > 0) {
        var s = "starting " + (failover.failures + 1)
        if (failover.maxTries != null) s += "/" + failover.maxTries
        s += ", failed " + Str.dateTime(failover.failureTime)
        return s
      }

      return "starting"
    }

    if (task != null) return "stopping"
    "stopped"
  }

  def waitFor(running: Boolean, timeout: Period): Boolean = {
    def matches: Boolean = if (running) task != null && task.running else task == null

    var t = timeout.ms
    while (t > 0 && !matches) {
      val delay = Math.min(100, t)
      Thread.sleep(delay)
      t -= delay
    }

    matches
  }

  def fromJson(node: Map[String, Object]): Unit = {
    id = node("id").asInstanceOf[String]
    active = node("active").asInstanceOf[Boolean]

    if (node.contains("host")) host = new Constraint(node("host").asInstanceOf[String])
    cpus = node("cpus").asInstanceOf[Number].doubleValue()
    mem = node("mem").asInstanceOf[Number].longValue()
    heap = node("heap").asInstanceOf[Number].longValue()

    if (node.contains("constraints")) constraints = Util.parseMap(node("constraints").asInstanceOf[String])
                                                    .mapValues(new Constraint(_)).view.force
    if (node.contains("options")) options = Util.parseMap(node("options").asInstanceOf[String])

    failover.fromJson(node("failover").asInstanceOf[Map[String, Object]])

    if (node.contains("task")) {
      task = new Broker.Task()
      task.fromJson(node("task").asInstanceOf[Map[String, Object]])
    }
  }

  def toJson: JSONObject = {
    val obj = new collection.mutable.LinkedHashMap[String, Any]()
    obj("id") = id
    obj("active") = active

    if (host != null) obj("host") = "" + host
    obj("cpus") = cpus
    obj("mem") = mem
    obj("heap") = heap

    if (!constraints.isEmpty) obj("constraints") = Util.formatMap(constraints)
    if (!options.isEmpty) obj("options") = Util.formatMap(options)

    obj("failover") = failover.toJson
    if (task != null) obj("task") = task.toJson

    new JSONObject(obj.toMap)
  }
}

object Broker {
  def nextTaskId(broker: Broker): String = "broker-" + broker.id + "-" + UUID.randomUUID()
  def nextExecutorId(broker: Broker): String = "broker-" + broker.id + "-" + UUID.randomUUID()

  def idFromTaskId(taskId: String): String = {
    val parts: Array[String] = taskId.split("-")
    if (parts.length < 2) throw new IllegalArgumentException(taskId)
    parts(1)
  }

  class Failover(_delay: Period = new Period("10s"), _maxDelay: Period = new Period("60s")) {
    var delay: Period = _delay
    var maxDelay: Period = _maxDelay
    var maxTries: Integer = null

    @volatile var failures: Int = 0
    @volatile var failureTime: Date = null

    def currentDelay: Period = {
      if (failures == 0) return new Period("0")

      val multiplier = 1 << (failures - 1)
      val d = delay.ms * multiplier

      if (d > maxDelay.ms) maxDelay else new Period(delay.value * multiplier + delay.unit)
    }

    def delayExpires: Date = {
      if (failures == 0) return new Date(0)
      new Date(failureTime.getTime + currentDelay.ms)
    }

    def isWaitingDelay(now: Date = new Date()): Boolean = delayExpires.getTime > now.getTime

    def isMaxTriesExceeded: Boolean = {
      if (maxTries == null) return false
      failures >= maxTries
    }

    def registerFailure(now: Date = new Date()): Unit = {
      failures += 1
      failureTime = now
    }

    def resetFailures(): Unit = {
      failures = 0
      failureTime = null
    }

    def fromJson(node: Map[String, Object]): Unit = {
      delay = new Period(node("delay").asInstanceOf[String])
      maxDelay = new Period(node("maxDelay").asInstanceOf[String])
      if (node.contains("maxTries")) maxTries = node("maxTries").asInstanceOf[Number].intValue()

      if (node.contains("failures")) failures = node("failures").asInstanceOf[Number].intValue()
      if (node.contains("failureTime")) failureTime = new Date(node("failureTime").asInstanceOf[Number].longValue())
    }

    def toJson: JSONObject = {
      val obj = new collection.mutable.LinkedHashMap[String, Any]()

      obj("delay") = "" + delay
      obj("maxDelay") = "" + maxDelay
      if (maxTries != null) obj("maxTries") = maxTries

      if (failures != 0) obj("failures") = failures
      if (failureTime != null) obj("failureTime") = failureTime.getTime

      new JSONObject(obj.toMap)
    }
  }

  class Task(
    _id: String = null,
    _host: String = null,
    _port: Int = -1,
    _attributes: util.Map[String, String] = Collections.emptyMap(),
    _running: Boolean = false
  ) {
    var id: String = _id
    @volatile var running: Boolean = _running

    var host: String = _host
    var port: Int = _port
    var attributes: util.Map[String, String] = _attributes

    def endpoint: String = host + ":" + port

    def fromJson(node: Map[String, Object]): Unit = {
      id = node("id").asInstanceOf[String]
      running = node("running").asInstanceOf[Boolean]

      host = node("host").asInstanceOf[String]
      port = node("port").asInstanceOf[Number].intValue()
      attributes = node("attributes").asInstanceOf[Map[String, String]]
    }

    def toJson: JSONObject = {
      val obj = new collection.mutable.LinkedHashMap[String, Any]()

      obj("id") = id
      obj("running") = running

      obj("host") = host
      obj("port") = port
      obj("attributes") = new JSONObject(attributes.toMap)

      new JSONObject(obj.toMap)
    }
  }

  type OtherAttributes = (String) => Array[String]
  def NoAttributes: OtherAttributes = _ => Array()
}