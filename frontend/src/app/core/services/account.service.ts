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

  getAccount(id: string): Observable<Account> {
    return this.http.get<Account>(`/api/accounts/${id}`);
  }

  createAccount(req: CreateAccountRequest): Observable<Account> {
    return this.http.post<Account>('/api/accounts', req);
  }

  getAccountTransactions(id: string): Observable<Transaction[]> {
    return this.http.get<Transaction[]>(`/api/accounts/${id}/transactions`);
  }

  deposit(id: string, amount: number): Observable<Account> {
    return this.http.post<Account>(`/api/accounts/${id}/deposit`, { amount });
  }

  deleteAccount(id: string): Observable<void> {
    return this.http.delete<void>(`/api/accounts/${id}`);
  }

  freezeAccount(id: string): Observable<Account> {
    return this.http.patch<Account>(`/api/accounts/${id}/freeze`, {});
  }
}
