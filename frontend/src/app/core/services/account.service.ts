import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Account, CreateAccountRequest, Transaction } from '../models';

@Injectable({ providedIn: 'root' })
export class AccountService {
  private http = inject(HttpClient);

  getAccounts(): Observable<Account[]> {
    return this.http.get<Account[]>('/api/accounts');
  }

  getAccount(id: number): Observable<Account> {
    return this.http.get<Account>(`/api/accounts/${id}`);
  }

  createAccount(req: CreateAccountRequest): Observable<Account> {
    return this.http.post<Account>('/api/accounts', req);
  }

  getAccountTransactions(id: number): Observable<Transaction[]> {
    return this.http.get<Transaction[]>(`/api/accounts/${id}/transactions`);
  }
}
