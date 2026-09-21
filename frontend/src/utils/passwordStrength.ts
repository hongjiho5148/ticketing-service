export interface PasswordRequirement {
  key: string;
  label: string;
  met: boolean;
}

export type PasswordStrengthLevel = "empty" | "weak" | "fair" | "good";

export interface PasswordStrength {
  level: PasswordStrengthLevel;
  label: string;
  metCount: number;
  requirements: PasswordRequirement[];
  isValid: boolean;
}

export function getPasswordStrength(password: string): PasswordStrength {
  const requirements: PasswordRequirement[] = [
    { key: "length", label: "8자 이상", met: password.length >= 8 },
    { key: "lower", label: "영문 소문자", met: /[a-z]/.test(password) },
    { key: "upper", label: "영문 대문자", met: /[A-Z]/.test(password) },
    { key: "digit", label: "숫자", met: /\d/.test(password) },
    { key: "special", label: "특수문자", met: /[^a-zA-Z0-9]/.test(password) },
  ];
  const metCount = requirements.filter((r) => r.met).length;
  const isValid = metCount === requirements.length;

  let level: PasswordStrengthLevel = "empty";
  let label = "";
  if (password.length > 0) {
    if (metCount <= 2) {
      level = "weak";
      label = "약함";
    } else if (metCount <= 4) {
      level = "fair";
      label = "보통";
    } else {
      level = "good";
      label = "양호";
    }
  }

  return { level, label, metCount, requirements, isValid };
}
