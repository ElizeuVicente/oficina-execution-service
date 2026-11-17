package com.oficina.execution;
import org.springframework.data.mongodb.repository.MongoRepository;
interface StockRepository extends MongoRepository<StockDocument, String> {}
