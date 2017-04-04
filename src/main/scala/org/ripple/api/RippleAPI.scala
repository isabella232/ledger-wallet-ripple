package org.ripple.api

import java.time.LocalDateTime

import org.json.JSONObject
import org.scalajs.dom
import org.scalajs.dom.raw.HTMLIFrameElement

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.scalajs.js
import concurrent.Future
import concurrent.Promise
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try



/**
  *
  * RippleAPI
  * ledger-wallet-ripple-chrome
  *
  * Created by Alix Mougel on 3/27/17..
  *
  * The MIT License (MIT)
  *
  * Copyright (c) 2016 Ledger
  *
  * Permission is hereby granted, free of charge, to any person obtaining a copy
  * of this software and associated documentation files (the "Software"), to deal
  * in the Software without restriction, including without limitation the rights
  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  * copies of the Software, and to permit persons to whom the Software is
  * furnished to do so, subject to the following conditions:
  *
  * The above copyright notice and this permission notice shall be included in all
  * copies or substantial portions of the Software.
  *
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  * SOFTWARE.
  *
  */


class RippleAPI() {
  sealed class RippleAPIObject()

  var promisesTable: Map[String,Promise[String]] = Map.empty

  //*************** setOptions *******************
  var setOptionsPromisesTable: Map[String,Promise[SetOptionsResponse]] = Map.empty

  case class APIOption(server: Option[String], feeCushion: Option[Double], trace: Option[Boolean],
                       proxy: Option[String], timeout: Option[Long]) extends RippleAPIObject

  def setOptions(options: APIOption): Future[SetOptionsResponse] ={
    val p: Promise[SetOptionsResponse] = Promise[SetOptionsResponse]
    val callId = getCallId()
    val methodName = "set_option"
    execute(methodName, options).map(decode[SetOptionsResponse](_).right.get)
  }


  def setOptionsHandler(callId: String, data: dom.MessageEvent) = {
    val response = decode[SetOptionsResponse](SetOptionsResponse.asJson.spaces4).right.get
    val p = this.setOptionsPromisesTable.get(callId).get
    this.promisesTable -=  callId
    this.setOptionsPromisesTable -=  callId
    p.success(response)
  }

  case class SetOptionsResponse(connected: Boolean, error: Option[String]) extends RippleAPIObject

  //-----------------------------------------------------
  //****************** classes **********
  case class Instructions(fee: Option[Double] = None,maxLedgerVersion: Option[Int] = None,
                          maxLedgerVersionOffset: Option[Int] = None, sequence: Option[Long] = None) extends RippleAPIObject

  case class Source(address: String, amount: LaxAmount, tag: Option[Int], maxAmount: LaxAmount) extends RippleAPIObject

  case class LaxAmount(currency: String, counterparty: Option[String], value: Option[String]) extends RippleAPIObject

  case class Destination(address: String, amount: LaxAmount, tag: Option[Int], minAmount: LaxAmount) extends RippleAPIObject

  case class Payment(source: Source, destination: Destination, allowPartialPayment: Option[Boolean],
                     invoiceID: Option[String], limitQuality: Option[Boolean],
                     memos: Option[Array[Memo]], noDirectRipple: Option[Boolean], paths: Option[String]) extends RippleAPIObject

  case class Memo(data: Option[String], format: Option[String], `type`: Option[String]) extends RippleAPIObject
  //--------------------
  //************** Universal "prepare" methods ********
  var preparePromisesTable: Map[String,Promise[PrepareResponse]] = Map.empty

  def preparePayment(address: String, payment: Payment, instructions: Option[Instructions] = None): Future[PrepareResponse] = {
    val paymentParam: PaymentParam = PaymentParam(address, payment, instructions)
    execute("preparePayment", paymentParam).map(decode[PrepareResponse](_).right.get)
  }

  case class PaymentParam(address: String, payment: Payment, instructions: Option[Instructions]) extends RippleAPIObject

  case class UniversalPrepareResponse(success: Boolean, response: PrepareResponse) extends RippleAPIObject

  case class PrepareResponse(txJSON: String, instructions: Instructions) extends RippleAPIObject

  def universalPrepareHandler(callId: String, data: dom.MessageEvent) = {
    val response: UniversalPrepareResponse = decode[UniversalPrepareResponse](data.data.toString()).right.get
    val p = this.preparePromisesTable.get(callId).get
    this.promisesTable -=  callId
    this.preparePromisesTable -=  callId
    p.success(response.response)
  }
  //----------------

  // *************** general tools **************

  def getCallId = {() =>LocalDateTime.now.toString() +
    LocalDateTime.now.getSecond.toString + LocalDateTime.now.getNano.toString}

  def execute(methodName: String, parameters: RippleAPIObject) = {
    val callId = getCallId()
    val p = Promise[String]()
    promisesTable += (callId->p)
    val options = js.Dynamic.literal(
      callId = callId,
      methodName = methodName,
      parameters = parameters.asJson.noSpaces
    )
    val target = dom.document.getElementById("ripple-api-sandbox").asInstanceOf[HTMLIFrameElement]
    target.contentWindow.postMessage(options,"*")
    p.future
  }

  def onMessage(msg: dom.MessageEvent): Unit = {
    val callId: String = msg.data.asInstanceOf[JSONObject].getString("success")
   //promisesTable.get(callId).get

  }
  dom.document.addEventListener("message", { (e: dom.MessageEvent) => this.onMessage(e)}) //can't figure out how to pass onMessage to the event listener
}