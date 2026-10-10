import 'package:aurabank_core/screens/auth/register_screen.dart';
import 'package:aurabank_core/services/auth_api_service.dart';
import 'package:aurabank_core/widgets/aurora_background.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// Pumps the form at phone size. The sky loops forever, so settle by time.
Future<List<http.Request>> _pumpRegister(WidgetTester tester, http.Response reply) async {
  final calls = <http.Request>[];
  AuthApiService().httpClient = MockClient((req) async {
    calls.add(req);
    return reply;
  });
  addTearDown(() => AuthApiService().httpClient = null);
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(const MaterialApp(home: RegisterScreen()));
  await tester.pump(const Duration(seconds: 1));
  return calls;
}

/// Scrolls [finder] into view, lets the scroll lay out, then taps it.
Future<void> _tap(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pump();
  await tester.tap(finder);
  await tester.pump(const Duration(seconds: 1));
}

Future<void> _tapCreate(WidgetTester tester) => _tap(tester, find.text('Create account'));

Finder _field(String label) => find.widgetWithText(TextFormField, label);

void main() {
  testWidgets('register form shows inline errors and never calls the backend while invalid', (tester) async {
    final calls = await _pumpRegister(tester, http.Response('{}', 500));

    await _tapCreate(tester);
    expect(find.text('Enter your first name'), findsOneWidget);
    expect(find.text('Enter a valid email address'), findsOneWidget);
    expect(find.text('Choose your date of birth'), findsOneWidget);
    expect(find.text('Choose an ID type'), findsOneWidget);
    expect(find.text('Use at least 8 characters'), findsOneWidget);

    await tester.enterText(_field('Email'), 'juan@example');
    await tester.enterText(_field('Mobile number'), '12345');
    await tester.enterText(_field('Password'), 'short');
    await _tapCreate(tester);
    expect(find.text('Enter a valid email address'), findsOneWidget);
    expect(find.text('Enter a mobile number, like +63 917 123 4567'), findsOneWidget);
    expect(find.text('Use at least 8 characters'), findsOneWidget);
    expect(calls, isEmpty);
  });

  testWidgets('a valid form posts snake_case JSON and shows the 409 detail inline', (tester) async {
    final calls = await _pumpRegister(
      tester,
      http.Response('{"status":409,"detail":"Email juan@example.ph is already registered."}', 409),
    );

    await tester.enterText(_field('First name'), 'Juan');
    await tester.enterText(_field('Last name'), 'Dela Cruz');
    await tester.enterText(_field('Email'), 'juan@example.ph');
    await tester.enterText(_field('Mobile number'), '+63 917 123 4567');
    await tester.enterText(_field('Home address'), '12 Ayala Ave, Makati');
    await tester.enterText(_field('ID number'), 'P1234567A');
    await tester.enterText(_field('Password'), 'Password123!');
    await _tap(tester, find.byTooltip('Pick date of birth'));
    await tester.tap(find.text('OK'));
    await tester.pump(const Duration(seconds: 1));
    await _tap(tester, find.byType(DropdownButtonFormField<String>));
    await tester.tap(find.text('Philippine passport').last);
    await tester.pump(const Duration(seconds: 1));

    await _tapCreate(tester);

    expect(calls, hasLength(1));
    expect(calls.single.url.path, '/api/v1/auth/register');
    expect(calls.single.body, contains('"phone_number":"+639171234567"'));
    expect(calls.single.body, contains('"government_id_type":"PASSPORT"'));
    expect(find.byKey(const ValueKey('registerError')), findsOneWidget);
    expect(find.text('Email juan@example.ph is already registered.'), findsOneWidget);
  });

  for (final size in const [Size(390, 844), Size(375, 667), Size(460, 1000)]) {
    testWidgets('phone ${size.width.toInt()}x${size.height.toInt()}: sheet docks below sky, corners sit on sky, not covered',
        (tester) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(const MaterialApp(home: RegisterScreen()));
      await tester.pump(const Duration(seconds: 1));

      final sky = tester.getRect(find.byType(AuroraBackground));
      final sheet = tester.getRect(find.byKey(const ValueKey('registerSheet')));
      final heading = tester.getRect(find.text('Open an account'));
      final firstName = tester.getRect(_field('First name'));

      expect(sky.topLeft, Offset.zero);
      expect(sky.width, size.width);
      expect(sky.bottom, greaterThan(sheet.top), reason: 'sky runs under sheet corners');
      expect(sheet.width, size.width);
      expect(heading.bottom, lessThan(sheet.top));
      // Ensure the sheet top is above the first field with proper padding, never clipped or covered
      expect(firstName.top - sheet.top, inInclusiveRange(30, 36));
    });
  }

  testWidgets('phone with keyboard open: register sheet scrolls and Create account remains reachable', (tester) async {
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    tester.view.physicalSize = const Size(375, 667);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(const MaterialApp(home: RegisterScreen()));
    await tester.pump(const Duration(seconds: 1));

    await tester.ensureVisible(find.text('Create account'));
    await tester.pump();
    expect(tester.getRect(find.text('Create account')).bottom, lessThanOrEqualTo(667 - 300));
  });

  testWidgets('desktop: floating card centred with heading inside', (tester) async {
    tester.view.physicalSize = const Size(1440, 900);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(const MaterialApp(home: RegisterScreen()));
    await tester.pump(const Duration(seconds: 1));

    final card = tester.getRect(find.byKey(const ValueKey('registerSheet')));
    final heading = tester.getRect(find.text('Open an account'));

    expect(card.width, 440);
    expect(card.center.dx, closeTo(720, 1));
    expect(card.contains(heading.center), isTrue);
  });
}

