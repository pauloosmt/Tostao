import type { FormEvent, ReactNode } from 'react';
import { cx } from './utils';

export interface WalletGateProps {
  title: string;
  subtitle?: string;
  /** Texto do fecho dourado, que é o botão de enviar. */
  submitLabel?: string;
  onSubmit?: () => void;
  /** Fecho indisponível, por exemplo durante o envio. */
  disabled?: boolean;
  /** Use "tx-gate--compact" no celular. */
  className?: string;
  /** Os campos (e um Alert de erro, se houver). */
  children: ReactNode;
}

/** A tela de entrar e de criar conta: o formulário dentro da silhueta da carteira. */
export function WalletGate({ title, subtitle, submitLabel = 'Entrar', onSubmit, disabled, className, children }: WalletGateProps) {
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit?.();
  };
  return (
    // noValidate: quem valida é o aplicativo, com as mensagens dele (não o balão do navegador).
    <form className={cx('tx-gate', className)} onSubmit={submit} noValidate>
      <div className="tx-gate__flap" aria-hidden="true" />
      <div className="tx-gate__body">
        <h1 className="tx-gate__title display-lg">{title}</h1>
        {subtitle ? <p className="tx-gate__sub text-body">{subtitle}</p> : null}
        <div className="tx-gate__fields">{children}</div>
      </div>
      <button type="submit" className="tx-gate__clasp text-label" disabled={disabled}>
        <i aria-hidden="true" />
        {submitLabel}
      </button>
    </form>
  );
}
