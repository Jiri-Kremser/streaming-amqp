/*
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

package org.apache.spark.streaming.amqp

import java.util.concurrent.ConcurrentHashMap

import io.radanalytics.streaming.amqp.{AMQPFlowControllerListener, AMQPReceiver}
import io.vertx.core.Handler
import io.vertx.proton._
import org.apache.qpid.proton.amqp.messaging.Accepted
import org.apache.qpid.proton.message.Message
import org.apache.spark.storage.{StorageLevel, StreamBlockId}
import org.apache.spark.streaming.receiver.{BlockGenerator, BlockGeneratorListener}
import org.slf4j.LoggerFactory

import scala.collection.mutable

/**
 * Reliable receiver for getting messages from an AMQP sender node
 *
 * @param host					    AMQP container hostname or IP address to connect
 * @param port					    AMQP container port to connect
 * @param username          Username for SASL PLAIN authentication
 * @param password          Password for SASL PLAIN authentication
 * @param address				    AMQP node address on which receive messages
 * @param messageConverter  Callback for converting AMQP message to custom type at application level
 * @param storageLevel	    RDD storage level
 */
class ReliableAMQPReceiver[T](
      host: String,
      port: Int,
      username: Option[String],
      password: Option[String],
      address: String,
      messageConverter: Message => Option[T],
      storageLevel: StorageLevel
    ) extends AMQPReceiver[T](host, port, username, password, address, messageConverter, storageLevel)
      with AMQPFlowControllerListener {

  private final val MaxStoreAttempts = 3

  private var blockGenerator: BlockGenerator = _

  private var deliveryBuffer: mutable.ArrayBuffer[ProtonDelivery] = _

  private var blockDeliveryMap: ConcurrentHashMap[StreamBlockId, Array[ProtonDelivery]] = _

  @transient private lazy val log = LoggerFactory.getLogger(getClass)

  override def onStart() {

    deliveryBuffer = new mutable.ArrayBuffer[ProtonDelivery]()

    blockDeliveryMap = new ConcurrentHashMap[StreamBlockId, Array[ProtonDelivery]]()

    blockGenerator = supervisor.createBlockGenerator(new GeneratedBlockHandler())
    blockGenerator.start()

    super.onStart()
  }
  
  override def onStop() {

    if (Option(blockGenerator).isDefined && !blockGenerator.isStopped()) {
      blockGenerator.stop()
    }

    super.onStop()
  }

  /**
    * Handler for blocks generated by the block generator
    */
  private final class GeneratedBlockHandler extends BlockGeneratorListener {

    def onAddData(data: Any, metadata: Any): Unit = {

      log.debug(data.toString())

      if (Option(metadata).isDefined) {

        // adding delivery into internal buffer
        val delivery = metadata.asInstanceOf[ProtonDelivery]
        deliveryBuffer += delivery
      }
    }

    def onGenerateBlock(blockId: StreamBlockId): Unit = {

      // cloning internal delivery buffer and mapping it to the generated block
      val deliveryBufferSnapshot = deliveryBuffer.toArray
      blockDeliveryMap.put(blockId, deliveryBufferSnapshot)
      deliveryBuffer.clear()
    }

    def onPushBlock(blockId: StreamBlockId, arrayBuffer: mutable.ArrayBuffer[_]): Unit = {

      var attempt = 0
      var stored = false
      var exception: Option[Exception] = None

      // try more times to store messages
      while (!stored && attempt < MaxStoreAttempts) {

        try {

          // buffer contains AMQP Message instances
          val messages = arrayBuffer.asInstanceOf[mutable.ArrayBuffer[Message]]

          // storing result conversion from AMQP Message instances
          // by the application provided converter
          store(messages.flatMap(x => messageConverter(x)))
          stored = true

        } catch {

          case ex: Exception => {

            attempt += 1
            exception = Option(ex)
          }

        }

        if (stored) {

          // running delivery dispositions on the Vert.x context
          // not in the current pushing block thread
          context.runOnContext(new Handler[Void] {

            override def handle(event: Void): Unit = {

              // for the deliveries related to the current generated block
              blockDeliveryMap.get(blockId).foreach(delivery => {

                // for unsettled messages, send ACCEPTED delivery status
                if (!delivery.remotelySettled()) {
                  delivery.disposition(Accepted.getInstance(), true)
                }
              })

              blockDeliveryMap.remove(blockId)
            }
          })

        } else {

          log.error(exception.get.getMessage(), exception.get)
          stop("Error while storing block into Spark", exception.get)
        }
      }


    }

    def onError(message: String, throwable: Throwable): Unit = {
      log.error(message, throwable)
      reportError(message, throwable)
    }
  }

  /**
    * Called when an AMQP message is received on the link
    *
    * @param delivery Proton delivery instance
    * @param message  Proton AMQP message
    */
  override def onAcquire(delivery: ProtonDelivery, message: Message): Unit = {

    // only AMQP message will be stored into BlockGenerator internal buffer;
    // delivery is passed as metadata to onAddData and saved here internally
    blockGenerator.addDataWithCallback(message, delivery)
  }
}