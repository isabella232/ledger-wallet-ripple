package co.ledger.wallet.web.ripple.controllers.wallet

import autoupdater.Updater
import biz.enef.angulate.Module.RichModule
import biz.enef.angulate.core.JQLite
import biz.enef.angulate.{Controller, Scope}
import co.ledger.wallet.core.device.utils.EventReceiver
import co.ledger.wallet.core.wallet.ripple.Operation
import co.ledger.wallet.core.wallet.ripple.Wallet.{NewOperationEvent, StartSynchronizationEvent, StopSynchronizationEvent}
import co.ledger.wallet.web.ripple.components.{SnackBar, WindowManager}
import co.ledger.wallet.web.ripple.i18n.DateFormat
import co.ledger.wallet.web.ripple.services.{SessionService, WindowService}
import org.scalajs
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.{JSON, timers}
import scala.util.{Failure, Success}
import js.JSConverters._
import co.ledger.wallet.web.ripple.core.utils.{ChromeGlobalPreferences}

/**
  *
  * OperationController
  * ledger-wallet-ripple-chrome
  *
  * Created by Pierre Pollastri on 03/05/2016.
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
class AccountController(override val windowService: WindowService,
                        override val sessionService: SessionService,
                        override val $scope: Scope,
                        $element: JQLite,
                        $routeParams: js.Dictionary[String])
  extends Controller with WalletController with EventReceiver {
  import timers._
  var node = new ChromeGlobalPreferences("Settings").string("node").getOrElse("")
  if(node == "ws://ripple.ledger.fr:8532") {
    node = "launch.ledger_default"
  }
  val accountId = $routeParams("id").toInt
  println("account path", js.JSON.stringify($routeParams))


  def refresh(): Unit = {
    sessionService.currentSession.get.wallet.synchronize()
  }
  var address = ""
  var isRefreshing = false
  var hideLoader = true
  var hideHistory = true

  var operations = js.Array[js.Dictionary[js.Any]]()

  private var reloadOperationNonce = 0
  def reloadOperations(): Unit = {
    operations = js.Array[js.Dictionary[js.Any]]()
    reloadOperationNonce += 1
    hideLoader = true
    val nonce = reloadOperationNonce
    sessionService.currentSession.get.wallet.account(accountId).flatMap {
      _.operations(-1, 10)
    } foreach { cursor =>
      var isLoading = false
      def loadMore(): Unit = {
        isLoading = true
        cursor.loadNextChunk() map { (ops) =>
          ops foreach { (op) =>
            if (!operations.toArray.exists(_ ("uid").asInstanceOf[String] == op.uid)) {
              operations.push(js.Dictionary[js.Any](
                "uid" -> op.uid,
                "hash" -> op.transaction.hash,
                "date" -> new js.Date(op.transaction.receivedAt.getTime),
                "amount" -> (
                  (if (op.`type` == Operation.SendType) -1 else 1) *
                    op.transaction.value.toBigInt -
                    (if (op.`type` == Operation.SendType) op.transaction
                      .fee.toBigInt else 0)
                  ).toString(),
                "isSend" -> (op.`type` == Operation.SendType)
              ))
            }
          }
          operations = operations.toArray.sortBy(-_("date").asInstanceOf[js.Date].getTime).toJSArray
        } recover {
          case ex => ex.printStackTrace
        } map { (all) =>
          hideLoader = cursor.loadedChunkCount >= cursor.chunkCount
          hideHistory = !hideLoader || balance == "0"
          isLoading = false
          refresh()
        } recover {
          case ex => ex.printStackTrace
        } andThen {
          case all => $scope.$digest()
        }
      }

      def refresh() = {
        val top = $element.asInstanceOf[js.Dynamic].scrollTop()
          .asInstanceOf[Double]
        val scrollHeight = $element.asInstanceOf[js.Dynamic].height()
          .asInstanceOf[Double]
        val height = $element(0).asInstanceOf[js.Dynamic].scrollHeight
          .asInstanceOf[Double]
        if (top + scrollHeight >= height * 0.60) {
          if (!isLoading && reloadOperationNonce == nonce && cursor
            .loadedChunkCount < cursor.chunkCount) {
            loadMore()
          }
        }
      }

      $element.asInstanceOf[js.Dynamic].scroll({ () =>
        refresh()
      })

      js.Dynamic.global.$(js.Dynamic.global.window).resize({ () =>
        refresh()
      })
      loadMore()
    }

  }

  var balance = sessionService.currentSession.get.sessionPreferences
    .lift("balance_cache").getOrElse("").toString

  def reloadBalance(): Unit = {
    sessionService.currentSession.get.wallet.account(accountId) flatMap {(account) =>
      account.balance()
    } onComplete {
      case Success(b) =>
        sessionService.currentSession.get.sessionPreferences("balance_cache") = b.toBigInt.toString()
        balance = b.toBigInt.toString()
        hideHistory = !hideLoader || balance == "0"
        $scope.$digest()
      case Failure(ex) =>
        ex.printStackTrace()
    }
  }

  def openTransactionDetails(hash: String): Unit = {
    try {
      WindowManager.open(s"https://charts.ripple.com/#/transactions/$hash")
    } catch {
      case e:Throwable => e.printStackTrace()
    }
  }

  def openAccountExplorer(): Unit = {
    try {
      WindowManager.open(s"https://bithomp.com/explorer/$address")
    } catch {
      case e:Throwable => e.printStackTrace()
    }
  }

  sessionService.currentSession.get.wallet.eventEmitter.register(this)

  sessionService.currentSession.get.wallet.account(0) foreach {(account) =>
    account.rippleAccount() foreach { (a) =>
      address = a.toString
      $scope.$digest()
    }
  }


  $scope.$on("$destroy", {() =>
    sessionService.currentSession.foreach(_.wallet.eventEmitter.unregister(this))
  })



  reloadBalance()

  reloadOperations()


  override def receive: Receive = {
    case StartSynchronizationEvent() =>
      isRefreshing = true
      setTimeout(0) {
        $scope.$digest()
      }
    case StopSynchronizationEvent() =>
      isRefreshing = false
      reloadBalance()
      setTimeout(0) {
        $scope.$digest()
      }
    case NewOperationEvent(account, event) =>
      if (account.index == accountId) {
        reloadOperations()
        reloadBalance()
      }

    case drop =>
  }

  sessionService.currentSession.get.wallet.isSynchronizing() foreach {(sync) =>
    isRefreshing = sync
    setTimeout(0) {
      $scope.$digest()
    }
  }


}

object AccountController {

  def init(module: RichModule) = {
    module.controllerOf[AccountController]("AccountController")
  }
  
}