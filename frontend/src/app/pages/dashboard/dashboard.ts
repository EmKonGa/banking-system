import { Component, inject, OnInit, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Card } from 'primeng/card';
import { Button } from 'primeng/button';
import { Tag } from 'primeng/tag';
import { TableModule } from 'primeng/table';
import { ProgressSpinner } from 'primeng/progressspinner';
import { AccountService } from '../../core/services/account.service';
import { PaymentService } from '../../core/services/payment.service';
import { Account, Transaction } from '../../core/models';

@Component({
  selector: 'app-dashboard',
  imports: [CurrencyPipe, DatePipe, RouterLink, Card, Button, Tag, TableModule, ProgressSpinner],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss'
})
export class DashboardPage implements OnInit {
  private accountSvc = inject(AccountService);
  private paymentSvc = inject(PaymentService);

  accounts = signal<Account[]>([]);
  recentTransactions = signal<Transaction[]>([]);
  loading = signal(true);

  get totalBalance(): number {
    return this.accounts().reduce((sum, a) => sum + a.balance, 0);
  }

  ngOnInit(): void {
    this.accountSvc.getAccounts().subscribe(accounts => {
      this.accounts.set(accounts);
    });

    this.paymentSvc.getTransactions().subscribe({
      next: txs => {
        this.recentTransactions.set(txs.slice(0, 5));
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
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
