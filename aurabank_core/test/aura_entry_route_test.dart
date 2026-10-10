import 'package:aurabank_core/navigation/aura_entry_route.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('welcome sequence plays, then hands over to the dashboard', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: const Scaffold(body: Text('login')),
      onGenerateRoute: (s) => auraEntryRoute(s, (_) => const Scaffold(body: Text('dashboard'))),
    ));

    tester.state<NavigatorState>(find.byType(Navigator)).pushReplacementNamed('/dashboard');
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 1200)); // mid-sequence
    expect(find.text('Welcome'), findsOneWidget);
    expect(find.text('Securing your session'), findsOneWidget);

    // Material ancestor present, so no debug underline on the greeting.
    final style = DefaultTextStyle.of(tester.element(find.text('Welcome'))).style;
    expect(style.decoration, isNot(TextDecoration.underline));

    await tester.pump(const Duration(milliseconds: 520)); // seal closed
    expect(find.text('You\'re in'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 1000)); // past the end
    await tester.pump();
    expect(find.text('Welcome'), findsNothing);
    expect(find.text('dashboard'), findsOneWidget);
    expect(find.text('login'), findsNothing);
  });
}
