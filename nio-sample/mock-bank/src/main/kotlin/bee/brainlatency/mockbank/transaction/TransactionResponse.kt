package bee.brainlatency.mockbank.transaction

data class TransactionResponse(
    val id: Long,
    val accountId: Long,
    val description: String,
    val amount: Double,
)
