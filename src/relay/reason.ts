/** How the relay names a refusal from Apple or Google: "apns_400_BadDeviceToken", "fcm_503". */
export function failureReason(service: string, status: number, detail: string | undefined): string {
  return detail ? `${service}_${status}_${detail}` : `${service}_${status}`
}
