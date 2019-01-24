package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState
import java.lang.IllegalArgumentException

/**
 * This is the flow which handles transfers of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUTransferFlow(val linearId: UniqueIdentifier, val newLender: Party): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // Placeholder code to avoid type error when running the tests. Remove before starting the flow task!

        //get inputState(IOUState) queried by linearID
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val iouStateAndRef = serviceHub.vaultService.queryBy<IOUState>(queryCriteria).states.single()
        val inputIouState = iouStateAndRef.state.data

        //Owner of inputIou has to be this node
        if(ourIdentity != inputIouState.lender){
            throw IllegalArgumentException("IOU transfer can only be initiated by the IOU lender.")
        }

        //ceate OutputIou
        val outputIouState = inputIouState.withNewLender(newLender = newLender)



        //create transfer command


        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val signers = (inputIouState.participants + newLender).map{it.owningKey}
        val transfarCommand = Command(IOUContract.Commands.Transfer(),signers)
        val builder = TransactionBuilder(notary = notary)
        builder.addInputState(iouStateAndRef)
        builder.addOutputState(outputIouState,IOUContract.IOU_CONTRACT_ID)
        builder.addCommand(transfarCommand)
        builder.verify(serviceHub)
        val partialSignedTx = serviceHub.signInitialTransaction(builder)
        val sessions = (inputIouState.participants + newLender - ourIdentity).map { initiateFlow(it)}.toSet()
        val FullsignedTx = subFlow(CollectSignaturesFlow(partialSignedTx,sessions))

        return subFlow(FinalityFlow(FullsignedTx))

    }
}

/**
 * This is the flow which signs IOU transfers.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUTransferFlow::class)
class IOUTransferFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }
        subFlow(signedTransactionFlow)
    }
}
