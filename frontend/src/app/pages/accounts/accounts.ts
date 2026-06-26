import { Component, inject, OnInit, signal } from '@angular/core';
import { CurrencyPipe, DatePipe, TitleCasePipe } from '@angular/common';
import { Card } from 'primeng/card';
import { Button } from 'primeng/button';
import { Tag } from 'primeng/tag';
import { TableModule } from 'primeng/table';
import { Dialog } from 'primeng/dialog';
import { Select } from 'primeng/select';
import { ProgressSpinner } from 'primeng/progressspinner';
import { FormsModule } from '@angular/forms';
import { AccountService } from '../../core/services/account.service';
import { Account, AccountType, Transaction } from '../../core/models';

@Component({
  selector: 'app-accounts',
  imports: [CurrencyPipe, DatePipe, TitleCasePipe, FormsModule, Card, Button, Tag, TableModule, Dialog, Select, ProgressSpinner],
  templateUrl: './accounts.html',
  styleUrl: './accounts.scss'
})
export class AccountsPage implements OnInit {
  private accountSvc = inject(AccountService);

  accounts = signal<Account[]>([]);
  selectedAccount = signal<Account | null>(null);
  transactions = signal<Transaction[]>([]);
  loadingTx = signal(false);
  showCreateDialog = signal(false);
  creating = signal(false);
  newAccountType = signal<AccountType>('SAVINGS');

  accountTypeOptions = [
    { label: 'Savings', value: 'SAVINGS' },
    { label: 'Checking', value: 'CHECKING' }
  ];

  ngOnInit(): void {
    this.loadAccounts();
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
