package net.corda.node.internal

import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatingFlow
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.debug
import net.corda.node.services.statemachine.StateMachineManager
import net.corda.node.services.statemachine.flowVersionAndInitiatingClass
import rx.Observable
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

interface FlowManager {
    fun <F : FlowLogic<*>> registerInitiatedFlowFactory(smm: StateMachineManager,
                                                        initiatingFlowClass: Class<out FlowLogic<*>>,
                                                        flowFactory: InitiatedFlowFactory<F>,
                                                        initiatedFlowClass: Class<F>,
                                                        track: Boolean): Observable<F>

    fun registerInitiatedCoreFlowFactory(clientFlowClass: KClass<out FlowLogic<*>>, flowFactory: (FlowSession) -> FlowLogic<*>)
    fun registerInitiatedCoreFlowFactory(clientFlowClass: KClass<out FlowLogic<*>>, flowFactory: (FlowSession) -> FlowLogic<*>, serverFlowClass: KClass<out FlowLogic<*>>?)

    fun getFlowFactoryForInitiatingFlow(initiatingFlowClass: Class<out FlowLogic<*>>): InitiatedFlowFactory<*>?
}

class NodeFlowManager : FlowManager {

    companion object {
        private val log = contextLogger()
    }

    private val flowFactories = ConcurrentHashMap<Class<out FlowLogic<*>>, RegisteredFlowContainer>()

    override fun <F : FlowLogic<*>> registerInitiatedFlowFactory(smm: StateMachineManager,
                                                                 initiatingFlowClass: Class<out FlowLogic<*>>,
                                                                 flowFactory: InitiatedFlowFactory<F>,
                                                                 initiatedFlowClass: Class<F>,
                                                                 track: Boolean): Observable<F> {
        val observable = if (track) {
            smm.changes.filter { it is StateMachineManager.Change.Add }.map { it.logic }.ofType(initiatedFlowClass)
        } else {
            Observable.empty()
        }

        return flowFactories[initiatingFlowClass]?.let { currentRegistered ->
            if (currentRegistered.type == FlowType.CORE) {
                throw IllegalStateException("Attempting to replace an existing platform flow: $initiatingFlowClass")
            }

            if (currentRegistered.initiatedFlowClass?.isAssignableFrom(initiatedFlowClass) != true) {
                throw IllegalStateException("$initiatingFlowClass is attempting to register multiple flow initiated by: $initiatingFlowClass")
            }
            //the class to be registered is a subtype of the current registered class
            flowFactories[initiatedFlowClass] = RegisteredFlowContainer(initiatingFlowClass, initiatedFlowClass, flowFactory, FlowType.CORDAPP)
            observable
        } ?: {
            flowFactories[initiatingFlowClass] = RegisteredFlowContainer(initiatingFlowClass, initiatedFlowClass, flowFactory, FlowType.CORDAPP)
            observable
        }.invoke()


    }

    override fun registerInitiatedCoreFlowFactory(clientFlowClass: KClass<out FlowLogic<*>>, flowFactory: (FlowSession) -> FlowLogic<*>, serverFlowClass: KClass<out FlowLogic<*>>?) {
        require(clientFlowClass.java.flowVersionAndInitiatingClass.first == 1) {
            "${InitiatingFlow::class.java.name}.version not applicable for core flows; their version is the node's platform version"
        }
        flowFactories[clientFlowClass.java] = RegisteredFlowContainer(clientFlowClass.java, serverFlowClass?.java, InitiatedFlowFactory.Core(flowFactory), FlowType.CORE)
        log.debug { "Installed core flow ${clientFlowClass.java.name}" }
    }

    override fun registerInitiatedCoreFlowFactory(clientFlowClass: KClass<out FlowLogic<*>>, flowFactory: (FlowSession) -> FlowLogic<*>) {
        registerInitiatedCoreFlowFactory(clientFlowClass, flowFactory, null)
    }

    override fun getFlowFactoryForInitiatingFlow(initiatingFlowClass: Class<out FlowLogic<*>>): InitiatedFlowFactory<*>? {
        return flowFactories[initiatingFlowClass]?.flowFactory
    }


    enum class FlowType {
        CORE, CORDAPP
    }

    data class RegisteredFlowContainer(val initiatingFlowClass: Class<out FlowLogic<*>>,
                                       val initiatedFlowClass: Class<out FlowLogic<*>>?,
                                       val flowFactory: InitiatedFlowFactory<FlowLogic<*>>,
                                       val type: FlowType)
}