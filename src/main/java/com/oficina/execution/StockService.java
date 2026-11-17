package com.oficina.execution;

import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class StockService {
    private final StockRepository repository;
    StockService(StockRepository repository) { this.repository = repository; }
    StockDocument create(UUID branch, UUID part, int amount) {
        try { return repository.insert(new StockDocument(branch, part, amount)); }
        catch (DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock already exists", e); }
    }
    StockDocument get(UUID branch, UUID part) {
        return repository.findById(StockDocument.key(branch, part)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    StockDocument reserve(UUID branch, UUID part, UUID reservation, int amount) {
        StockDocument stock = get(branch, part);
        try { stock.reserve(reservation, amount); return repository.save(stock); }
        catch (InsufficientStockException e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e); }
        catch (IllegalStateException | OptimisticLockingFailureException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e); }
    }
    StockDocument release(UUID branch, UUID part, UUID reservation) {
        StockDocument stock = get(branch, part);
        try { stock.release(reservation); return repository.save(stock); }
        catch (IllegalStateException | OptimisticLockingFailureException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e); }
    }
    StockDocument consume(UUID branch, UUID part, UUID reservation) {
        StockDocument stock = get(branch, part);
        try { stock.consume(reservation); return repository.save(stock); }
        catch (IllegalStateException | OptimisticLockingFailureException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e); }
    }
}
