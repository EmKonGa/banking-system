import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { DepositRequest, Transaction, TransferRequest } from '../models';

@Injectable({ providedIn: 'root' })
export class PaymentService {
  private http = inject(HttpClient);

  transfer(req: TransferRequest): Observable<Transaction> {
    return this.http.post<Transaction>('/api/payments/transfer', req);
  }

  // Admin-only. Deposits are ledgered like transfers, so this returns the transaction — not the
  // updated account — and needs an idempotency key for the same reason a transfer does.
  deposit(req: DepositRequest): Observable<Transaction> {
    return this.http.post<Transaction>('/api/payments/deposit', req);
  }

  getTransactions(): Observable<Transaction[]> {
    return this.http.get<{ content: Transaction[] }>('/api/payments/transactions').pipe(
      map(page => page.content)
    );
  }
}
