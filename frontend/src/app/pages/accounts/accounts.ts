import { Component, inject, OnInit, signal } from '@angular/core';
import { CurrencyPipe, DatePipe, TitleCasePipe } from '@angular/common';
import { Card } from 'primeng/card';
import { Button } from 'primeng/button';
import { Tag } from 'primeng/tag';
import { TableModule } from 'primeng/table';
import { Dialog } from 'primeng/dialog';
import { Select } from 'primeng/select';
import { InputNumber } from 'primeng/inputnumber';
import { ProgressSpinner } from 'primeng/progressspinner';
import { FormsModule } from '@angular/forms';
import { AccountService } from '../../core/services/account.service';
import { AuthService } from '../../core/services/auth.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { Account, AccountType, Transaction } from '../../core/models';

@Component({
  selector: 'app-accounts',
  imports: [CurrencyPipe, DatePipe, TitleCasePipe, FormsModule, Card, Button, Tag, TableModule, Dialog, Select, InputNumber, ProgressSpinner],
  templateUrl: './accounts.html',
  styleUrl: './accounts.scss'
})
export class AccountsPage implements OnInit {
  private accountSvc = inject(AccountService);
  private authSvc = inject(AuthService);
  private wsSvc = inject(WebSocketService);

  accounts = signal<Account[]>([]);
  selectedAccount = signal<Account | null>(null);
  transactions = signal<Transaction[]>([]);
  loadingTx = signal(false);

  showCreateDialog = signal(false);
  creating = signal(false);
  newAccountType = signal<AccountType>('SAVINGS');

  showDepositDialog = signal(false);
  depositAccount = signal<Account | null>(null);
  depositAmount = signal<number>(0);
  depositing = signal(false);

  isAdmin = this.authSvc.isAdmin();

  accountTypeOptions = [
    { label: 'Savings', value: 'SAVINGS' },
    { label: 'Checking', value: 'CHECKING' }
  ];

  ngOnInit(): void {
    this.loadAccounts();
    this.wsSvc.balance$.subscribe(updated => {
      this.accounts.update(list => list.map(a => a.id === updated.id ? updated : a));
      if (this.selectedAccount()?.id === updated.id) {
        this.selectedAccount.set(updated);
        this.loadingTx.set(true);
        this.accountSvc.getAccountTransactions(updated.id).subscribe(txs => {
          this.transactions.set(txs);
          this.loadingTx.set(false);
        });
      }
    });
  }

  loadAccounts(): void {
    this.accountSvc.getAccounts().subscribe(list => this.accounts.set(list));
  }

  selectAccount(account: Account): void {
    this.selectedAccount.set(account);
    this.loadingTx.set(true);
    this.accountSvc.getAccountTransactions(account.id).subscribe(txs => {
      this.transactions.set(txs);
      this.loadingTx.set(false);
    });
  }

  createAccount(): void {
    this.creating.set(true);
    this.accountSvc.createAccount({ type: this.newAccountType() }).subscribe({
      next: () => {
        this.loadAccounts();
        this.showCreateDialog.set(false);
        this.creating.set(false);
      },
      error: () => this.creating.set(false)
    });
  }

  deleteAccount(account: Account, event: Event): void {
    event.stopPropagation();
    if (!confirm(`Close account ${account.accountNumber}? This cannot be undone.`)) return;
    this.accountSvc.deleteAccount(account.id).subscribe({
      next: () => {
        this.accounts.update(list => list.map(a => a.id === account.id ? { ...a, status: 'CLOSED' } : a));
        if (this.selectedAccount()?.id === account.id) this.selectedAccount.set({ ...account, status: 'CLOSED' });
      },
      error: (err) => alert(err.error?.message ?? 'Close failed')
    });
  }

  freezeAccount(account: Account, event: Event): void {
    event.stopPropagation();
    this.accountSvc.freezeAccount(account.id).subscribe({
      next: (updated) => {
        this.accounts.update(list => list.map(a => a.id === updated.id ? updated : a));
        if (this.selectedAccount()?.id === updated.id) this.selectedAccount.set(updated);
      },
      error: (err) => alert(err.error?.message ?? 'Freeze failed')
    });
  }

  openDepositDialog(account: Account, event: Event): void {
    event.stopPropagation();
    this.depositAccount.set(account);
    this.depositAmount.set(0);
    this.showDepositDialog.set(true);
  }

  confirmDeposit(): void {
    const account = this.depositAccount();
    if (!account || this.depositAmount() <= 0) return;
    this.depositing.set(true);
    this.accountSvc.deposit(account.id, this.depositAmount()).subscribe({
      next: (updated) => {
        this.accounts.update(list => list.map(a => a.id === updated.id ? updated : a));
        if (this.selectedAccount()?.id === updated.id) this.selectedAccount.set(updated);
        this.showDepositDialog.set(false);
        this.depositing.set(false);
      },
      error: () => this.depositing.set(false)
    });
  }

  accountSeverity(status: string): 'success' | 'warn' | 'danger' | 'secondary' {
    switch (status) {
      case 'ACTIVE': return 'success';
      case 'FROZEN': return 'warn';
      case 'CLOSED': return 'danger';
      default: return 'secondary';
    }
  }

  txSeverity(status: string): 'success' | 'warn' | 'danger' | 'secondary' {
    switch (status) {
      case 'COMPLETED': return 'success';
      case 'PENDING': return 'warn';
      case 'FAILED': return 'danger';
      default: return 'secondary';
    }
  }
}
