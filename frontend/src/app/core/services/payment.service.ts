import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Transaction, TransferRequest } from '../models';

@Injectable({ providedIn: 'root' })
export class PaymentService {
  private http = inject(HttpClient);

  transfer(req: TransferRequest): Observable<Transaction> {
    return this.http.post<Transaction>('/api/payments/transfer', req);
  }

  getTransactions(): Observable<Transaction[]> {
    return this.http.get<Transaction[]>('/api/payments/transactions');
  }
}
