import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:aurabank_core/navigation/root_navigator.dart';
import 'package:aurabank_core/services/device_storage.dart';
import 'package:aurabank_core/services/bank_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await DeviceStorage.init();
    await DeviceStorage.clearSession();
    await DeviceStorage.clearBiometricProfile();
  });

  test('root navigator key is available', () {
    expect(rootNavigatorKey, isNotNull);
  });

  test('DeviceStorage preserves biometric profile across clearSession logout', () async {
    await DeviceStorage.saveBiometricProfile(
      userId: 'usr-maria-999',
      email: 'maria.santos@email.com',
      name: 'Maria Santos',
      phone: '+63 918 555 4321',
      address: 'Cebu City, Philippines',
      accountId: '1000-2000-3002',
      balance: 1250000.0,
    );
    DeviceStorage.saveLastLoginEmail('maria.santos@email.com');
    DeviceStorage.saveLastLoginName('Maria Santos');

    // Simulate active session
    await DeviceStorage.saveAccessToken('test-token');
    await DeviceStorage.saveUserSessionProfile(
      userId: 'usr-maria-999',
      email: 'maria.santos@email.com',
      name: 'Maria Santos',
    );

    // Logout clears active session
    await DeviceStorage.clearSession();

    // Active session is cleared
    expect(DeviceStorage.getAccessToken(), isNull);
    expect(DeviceStorage.getUserName(), isNull);
    expect(DeviceStorage.getUserEmail(), isNull);

    // Biometric and last login credentials persist
    expect(DeviceStorage.getBiometricUserEmail(), 'maria.santos@email.com');
    expect(DeviceStorage.getBiometricUserName(), 'Maria Santos');
    expect(DeviceStorage.getBiometricUserBalance(), 1250000.0);
    expect(DeviceStorage.getLastLoginEmail(), 'maria.santos@email.com');
  });

  test('BankService restoreUserProfileFromStorage restores enrolled biometric user dynamically', () async {
    final bankService = BankService();

    await DeviceStorage.saveBiometricProfile(
      userId: 'usr-maria-999',
      email: 'maria.santos@email.com',
      name: 'Maria Santos',
      phone: '+63 918 555 4321',
      address: 'Cebu City, Philippines',
      accountId: '1000-2000-3002',
      balance: 1250000.0,
    );

    // After logout, restore using biometric profile
    bankService.restoreUserProfileFromStorage();

    expect(bankService.user.name, 'Maria Santos');
    expect(bankService.user.email, 'maria.santos@email.com');
    expect(bankService.activeAccountId, '1000-2000-3002');
    expect(bankService.availableBalance, 1250000.0);
    expect(bankService.cards.first.holderName, 'Maria Santos');
  });

  test('BankService restoreUserProfileFromStorage restores demo persona dynamically by preferred email', () {
    final bankService = BankService();

    bankService.restoreUserProfileFromStorage(preferredEmail: 'juan.dc@email.com');

    expect(bankService.user.name, 'Juan Dela Cruz');
    expect(bankService.user.email, 'juan.dc@email.com');
    expect(bankService.activeAccountId, '1000-2000-3001');
    expect(bankService.cards.first.holderName, 'Juan Dela Cruz');
  });
}
