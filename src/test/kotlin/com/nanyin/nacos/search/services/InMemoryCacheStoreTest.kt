package com.nanyin.nacos.search.services

internal class InMemoryCacheStoreTest : CacheStoreContractTest() {
    override fun newStore(): CacheStore = InMemoryCacheStore()
}
