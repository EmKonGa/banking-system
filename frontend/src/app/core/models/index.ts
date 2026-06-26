export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  name: string;
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
  id: number;
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
  id: number;
  type: TransactionType;
  amount: number;
  status: TransactionStatus;
  fromAccountId: number;
  toAccountId: number;
  description: string;
  createdAt: string;
}

export interface TransferRequest {
  fromAccountId: number;
  toAccountId: number;
  amount: number;
  description?: string;
}

export type NotificationType = 'PAYMENT_SENT' | 'PAYMENT_RECEIVED' | 'ACCOUNT_CREATED' | 'GENERAL';

export interface Notification {
  id: number;
  type: NotificationType;
  message: string;
  read: boolean;
  createdAt: string;
}
