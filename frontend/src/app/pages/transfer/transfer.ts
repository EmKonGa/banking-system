import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { Card } from 'primeng/card';
import { Button } from 'primeng/button';
import { Select } from 'primeng/select';
import { InputNumber } from 'primeng/inputnumber';
import { InputText } from 'primeng/inputtext';
import { Tag } from 'primeng/tag';
import { TableModule } from 'primeng/table';
import { Toast } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { AccountService } from '../../core/services/account.service';
import { PaymentService } from '../../core/services/payment.service';
import { Account, Transaction } from '../../core/models';

@Component({
  selector: 'app-transfer',
  imports: [ReactiveFormsModule, CurrencyPipe, DatePipe, Card, Button, Select, InputNumber, InputText, Tag, TableModule, Toast],
  providers: [MessageService],
  templateUrl: './transfer.html',
  styleUrl: './transfer.scss'
})
export class TransferPage implements OnInit {
  private fb = inject(FormBuilder);
  private accountSvc = inject(AccountService);
  private paymentSvc = inject(PaymentService);
  private msg = inject(MessageService);

  accounts = signal<Account[]>([]);
  transactions = signal<Transaction[]>([]);
  loading = signal(false);
  submitting = signal(false);

  accountOptions = signal<{ label: string; value: string }[]>([]);

  form = this.fb.group({
    fromAccountId: [null as string | null, Validators.required],
    toAccountNumber: [null as string | null, Validators.required],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    description: ['']
  });

  ngOnInit(): void {
    this.accountSvc.getAccounts().subscribe(list => {
      this.accounts.set(list);
      this.accountOptions.set(
        list.map(a => ({ label: `${a.accountNumber} (${a.type}) — $${a.balance.toFixed(2)}`, value: a.id }))
      );
    });

    this.loadTransactions();
  }

  loadTransactions(): void {
    this.loading.set(true);
    this.paymentSvc.getTransactions().subscribe(txs => {
      this.transactions.set(txs);
      this.loading.set(false);
    });
  }

  submit(): void {
    if (this.form.invalid) return;
    const { fromAccountId, toAccountNumber, amount, description } = this.form.getRawValue();
    this.submitting.set(true);

    this.paymentSvc.transfer({
      fromAccountId: fromAccountId!,
      toAccountNumber: toAccountNumber!,
      amount: amount!,
      description: description || undefined
    }).subscribe({
      next: () => {
        this.msg.add({ severity: 'success', summary: 'Transfer sent', detail: `$${amount} transferred successfully` });
        this.form.reset();
        this.loadTransactions();
        this.submitting.set(false);
      },
      error: err => {
        this.msg.add({ severity: 'error', summary: 'Transfer failed', detail: err.error?.message ?? 'An error occurred' });
        this.submitting.set(false);
      }
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
