package com.virtualcamerapro.broker

class IntegrationFailure(val code: String, val component: String, message: String, val recovery: String) : IllegalStateException(message)
