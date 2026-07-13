export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  fullName: string;
  email: string;
  password: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
}

export type AccountType = 'SAVINGS' | 'CHECKING';
export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED';

export interface Account {
  id: string;
  accountNumber: string;
  type: AccountType;
  balance: number;
  status: AccountStatus;
  createdAt: string;
}

export interface CreateAccountRequest {
  type: AccountType;
}

export type TransactionType = 'CREDIT' | 'DEBIT' | 'TRANSFER';
export type TransactionStatus = 'PENDING' | 'COMPLETED' | 'FAILED';

export interface Transaction {
  id: string;
  type: TransactionType;
  amount: number;
  status: TransactionStatus;
  fromAccountNumber: string;
  toAccountNumber: string;
  description: string;
  createdAt: string;
}

export interface TransferRequest {
  fromAccountId: string;
  toAccountNumber: string;
  amount: number;
  description?: string;
  idempotencyKey?: string;
}

export interface BalanceUpdate {
  accountId: string;
  accountNumber: string;
  balance: number;
}

export type NotificationType = 'PAYMENT_SENT' | 'PAYMENT_RECEIVED' | 'ACCOUNT_CREATED' | 'GENERAL';

export interface Notification {
  id: string;
  type: NotificationType;
  message: string;
  read: boolean;
  createdAt: string;
}
