package com.twitter.finagle.zookeeper

import java.net.{InetSocketAddress, SocketAddress}
import com.twitter.common.zookeeper.ServerSet
import java.util.concurrent.atomic.AtomicReference
import com.google.common.collect.ImmutableSet
import com.twitter.common.net.pool.DynamicHostSet
import scala.collection.JavaConversions._
import com.twitter.thrift.ServiceInstance
import com.twitter.thrift.Status.ALIVE
import com.twitter.finagle.builder.Cluster
import collection.mutable.HashSet
import com.twitter.concurrent.Spool
import com.twitter.util.{Future, Return, Promise}

/**
 * A Cluster of SocketAddresses that provide a certain service. Cluster
 * membership is indicated by children Zookeeper node.
 */
class ZookeeperServerSetCluster(serverSet: ServerSet) extends Cluster[SocketAddress] {
  /**
   * LIFO "queue" of length one. Last-write-wins when more than one item
   * is enqueued.
   */
  private[this] val queuedChange = new AtomicReference[ImmutableSet[ServiceInstance]](null)
  // serverSet.monitor will block until initial membership is available
  private[zookeeper] val thread: Thread = new Thread {
    override def run{
      serverSet.monitor(new DynamicHostSet.HostChangeMonitor[ServiceInstance] {
        def onChange(serverSet: ImmutableSet[ServiceInstance]) = {
          val lastValue = queuedChange.getAndSet(serverSet)
          val firstToChange = lastValue eq null
          if (firstToChange) {
            var mostRecentValue: ImmutableSet[ServiceInstance] = null
            do {
              mostRecentValue = queuedChange.get
              performChange(mostRecentValue)
            } while (!queuedChange.compareAndSet(mostRecentValue, null))
          }
        }
      })
    }
  }
  thread.start()

  private[this] val underlyingSet = new HashSet[SocketAddress]
  private[this] var changes = new Promise[Spool[Cluster.Change[SocketAddress]]]

  private[this] def performChange(serverSet: ImmutableSet[ServiceInstance]) = synchronized {
    val newSet =  serverSet map { serviceInstance =>
      val endpoint = serviceInstance.getServiceEndpoint
      new InetSocketAddress(endpoint.getHost, endpoint.getPort): SocketAddress
    }
    val added = newSet &~ underlyingSet
    val removed = underlyingSet &~ newSet
    added foreach { address =>
      underlyingSet += address
      appendUpdate(Cluster.Add(address))
    }
    removed foreach { address =>
      underlyingSet -= address
      appendUpdate(Cluster.Rem(address))
    }
  }

  private[this] def appendUpdate(update: Cluster.Change[SocketAddress]) = {
    val newTail = new Promise[Spool[Cluster.Change[SocketAddress]]]
    changes() = Return(update *:: newTail)
    changes = newTail
  }

  def join(address: SocketAddress) {
    require(address.isInstanceOf[InetSocketAddress])

    serverSet.join(
      address.asInstanceOf[InetSocketAddress],
      Map[String, InetSocketAddress](),
      ALIVE)
  }

  def snap: (Seq[SocketAddress], Future[Spool[Cluster.Change[SocketAddress]]]) = synchronized {
    (underlyingSet.toSeq, changes)
  }
}
