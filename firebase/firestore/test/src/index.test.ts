import { describe, test, afterAll, beforeEach } from 'vitest';
import { assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDoc, getDocs, setDoc, updateDoc } from 'firebase/firestore';
import { setup } from './setup.js';

// Documents shaped exactly like the ones the app writes (see FirestoreBackup.kt and the
// *RepositoryLocal classes): optional values are sent as explicit nulls.
const now = Date.now();
const subscriptionId = '0b9e3c56-1f0d-4f0e-9a52-7d7c2b1e4a11';

const entry = { timestampEpochMilli: now, subscriptionId };
const entryId = `${now}_${subscriptionId}`;
const untaggedEntry = { timestampEpochMilli: now, subscriptionId: null };
const untaggedEntryId = `${now}_none`;

const subscription = {
  id: subscriptionId,
  displayName: 'Abonnement piscine.pdf',
  addedAtEpochMilli: now,
  expiresAtEpochMilli: now + 30 * 24 * 3600 * 1000,
  maxEntries: null,
  price: 60.5,
};

describe('Firestore rules tests', async () => {
  const env = await setup();

  // The app signs users in with Google only, and the rules let only such accounts write.
  const google = { firebase: { sign_in_provider: 'google.com' } };
  const me = env.authenticatedContext('alice', google).firestore();
  const other = env.authenticatedContext('bob', google).firestore();
  const anonymousAccount = env.authenticatedContext('alice', { firebase: { sign_in_provider: 'anonymous' } }).firestore();
  const noProviderAccount = env.authenticatedContext('alice').firestore();
  const anonymous = env.unauthenticatedContext().firestore();

  const myEntry = (id = entryId) => doc(me, `users/alice/entries/${id}`);
  const mySubscription = (id = subscriptionId) => doc(me, `users/alice/subscriptions/${id}`);

  beforeEach(async () => {
    await env.clearFirestore();
    await env.withSecurityRulesDisabled(async ctx => {
      const db = ctx.firestore();
      await setDoc(doc(db, `users/alice/entries/${entryId}`), entry);
      await setDoc(doc(db, `users/alice/subscriptions/${subscriptionId}`), subscription);
    });
  });

  afterAll(async () => {
    await env.clearFirestore();
    await env.cleanup();
  });

  describe('What the app does', () => {
    test('Users can back up an entry tagged with a subscription', async () => {
      await env.clearFirestore();
      await assertSucceeds(setDoc(myEntry(), entry));
    });

    test('Users can back up an entry with no subscription', async () => {
      await assertSucceeds(setDoc(myEntry(untaggedEntryId), untaggedEntry));
    });

    test('Users can re-save an existing entry', async () => {
      await assertSucceeds(setDoc(myEntry(), entry));
    });

    test('Users can back up a subscription', async () => {
      await env.clearFirestore();
      await assertSucceeds(setDoc(mySubscription(), subscription));
    });

    test('Users can back up an entry-limited subscription with no price or date', async () => {
      const id = '5f1c2a3b-0000-4000-8000-000000000001';
      await assertSucceeds(setDoc(mySubscription(id), {
        ...subscription, id, expiresAtEpochMilli: null, maxEntries: 10, price: null,
      }));
    });

    test('Users can back up a subscription that already expired', async () => {
      const id = '5f1c2a3b-0000-4000-8000-000000000002';
      await assertSucceeds(setDoc(mySubscription(id), {
        ...subscription, id, expiresAtEpochMilli: now - 24 * 3600 * 1000,
      }));
    });

    test('Users can delete their own entry and subscription', async () => {
      await assertSucceeds(deleteDoc(myEntry()));
      await assertSucceeds(deleteDoc(mySubscription()));
    });

    test('Users can read their own backup', async () => {
      await assertSucceeds(getDoc(myEntry()));
      await assertSucceeds(getDocs(collection(me, 'users/alice/subscriptions')));
    });
  });

  describe('Access control', () => {
    test('Signed-out users cannot read or write anything', async () => {
      await assertFails(getDoc(doc(anonymous, `users/alice/entries/${entryId}`)));
      await assertFails(getDocs(collection(anonymous, 'users/alice/entries')));
      await assertFails(setDoc(doc(anonymous, `users/alice/entries/${untaggedEntryId}`), untaggedEntry));
    });

    test("Users cannot read another user's backup", async () => {
      await assertFails(getDoc(doc(other, `users/alice/entries/${entryId}`)));
      await assertFails(getDocs(collection(other, 'users/alice/subscriptions')));
    });

    test("Users cannot write into another user's backup", async () => {
      await assertFails(setDoc(doc(other, `users/alice/entries/${untaggedEntryId}`), untaggedEntry));
      await assertFails(setDoc(doc(other, `users/alice/subscriptions/${subscriptionId}`), subscription));
    });

    test("Users cannot delete another user's backup", async () => {
      await assertFails(deleteDoc(doc(other, `users/alice/entries/${entryId}`)));
      await assertFails(deleteDoc(doc(other, `users/alice/subscriptions/${subscriptionId}`)));
    });

    test('Users cannot list every user', async () => {
      await assertFails(getDocs(collection(me, 'users')));
    });

    test('Users cannot write their users/{uid} document or other collections', async () => {
      await assertFails(setDoc(doc(me, 'users/alice'), { isAdmin: true }));
      await assertFails(setDoc(doc(me, 'users/alice/notes/x'), { text: 'hi' }));
      await assertFails(setDoc(doc(me, 'somethingElse/x'), { text: 'hi' }));
    });
  });

  describe('Entry validation', () => {
    test('Entries must keep their ID in sync with their fields', async () => {
      await assertFails(setDoc(myEntry('anything'), untaggedEntry));
      await assertFails(setDoc(myEntry(untaggedEntryId), entry));
    });

    test('Entries must have both fields, and no others', async () => {
      await assertFails(setDoc(myEntry(untaggedEntryId), { timestampEpochMilli: now }));
      await assertFails(setDoc(myEntry(untaggedEntryId), { ...untaggedEntry, extra: 'x' }));
    });

    test('Entry timestamps must be past, positive integers', async () => {
      const future = now + 2 * 24 * 3600 * 1000;
      await assertFails(setDoc(myEntry(`${future}_none`), { timestampEpochMilli: future, subscriptionId: null }));
      await assertFails(setDoc(myEntry('-5_none'), { timestampEpochMilli: -5, subscriptionId: null }));
      await assertFails(setDoc(myEntry(`${now}.5_none`), { timestampEpochMilli: now + 0.5, subscriptionId: null }));
      await assertFails(setDoc(myEntry(`${now}_none`), { timestampEpochMilli: `${now}`, subscriptionId: null }));
    });

    test('Entry subscription IDs must be short strings', async () => {
      const long = 'x'.repeat(65);
      await assertFails(setDoc(myEntry(`${now}_${long}`), { timestampEpochMilli: now, subscriptionId: long }));
      await assertFails(setDoc(myEntry(`${now}_42`), { timestampEpochMilli: now, subscriptionId: 42 }));
    });

    test('Entries cannot be updated into an invalid state', async () => {
      await assertFails(updateDoc(myEntry(), { subscriptionId: null }));
      await assertFails(updateDoc(myEntry(), { extra: 'x'.repeat(100000) }));
    });
  });

  describe('Swim duration', () => {
    // 6 hours, the cap the app puts on a swim (MAX_SWIM_DURATION).
    const maxSwim = 6 * 3600 * 1000;
    const withSwim = (swimDurationMillis: unknown) =>
      setDoc(myEntry(untaggedEntryId), { ...untaggedEntry, swimDurationMillis });

    test('Entries can be backed up before the swim is reported', async () => {
      await assertSucceeds(withSwim(null));
    });

    test('Entries can carry a swim duration of up to 6 hours', async () => {
      await assertSucceeds(withSwim(1));
      await assertSucceeds(withSwim(45 * 60 * 1000));
      await assertSucceeds(withSwim(maxSwim));
    });

    test('Swim durations must be positive and at most 6 hours', async () => {
      await assertFails(withSwim(-1));
      await assertFails(withSwim(0));
      await assertFails(withSwim(maxSwim + 1));
    });

    test('Swim durations must be integers', async () => {
      await assertFails(withSwim(1500.5));
      await assertFails(withSwim('2700000'));
      await assertFails(withSwim(true));
    });

    test('An existing entry can be re-saved with its swim duration', async () => {
      await assertSucceeds(setDoc(myEntry(), { ...entry, swimDurationMillis: null }));
      await assertSucceeds(setDoc(myEntry(), { ...entry, swimDurationMillis: 45 * 60 * 1000 }));
      await assertSucceeds(updateDoc(myEntry(), { swimDurationMillis: 50 * 60 * 1000 }));
    });

    test('An existing entry cannot be updated with an invalid swim duration', async () => {
      await assertFails(updateDoc(myEntry(), { swimDurationMillis: 0 }));
      await assertFails(updateDoc(myEntry(), { swimDurationMillis: maxSwim + 1 }));
      await assertFails(updateDoc(myEntry(), { swimDurationMillis: '2700000' }));
    });
  });

  describe('Swim distance', () => {
    const withDistance = (fields: Record<string, unknown>) =>
      setDoc(myEntry(untaggedEntryId), { ...untaggedEntry, ...fields });

    test('Entries can be backed up while awaiting their distance', async () => {
      await assertSucceeds(
        withDistance({ swimDistanceMeters: null, awaitingDistance: true }),
      );
    });

    test('Entries can carry a distance of up to 50 km, with the marker cleared', async () => {
      await assertSucceeds(withDistance({ swimDistanceMeters: 1, awaitingDistance: false }));
      await assertSucceeds(withDistance({ swimDistanceMeters: 1200, awaitingDistance: false }));
      await assertSucceeds(withDistance({ swimDistanceMeters: 50000, awaitingDistance: false }));
    });

    test('Distances must be positive integers of at most 50000', async () => {
      await assertFails(withDistance({ swimDistanceMeters: 0 }));
      await assertFails(withDistance({ swimDistanceMeters: -5 }));
      await assertFails(withDistance({ swimDistanceMeters: 50001 }));
      await assertFails(withDistance({ swimDistanceMeters: 12.5 }));
      await assertFails(withDistance({ swimDistanceMeters: '1200' }));
    });

    test('The awaiting marker must be a boolean', async () => {
      await assertFails(withDistance({ awaitingDistance: 'yes' }));
      await assertFails(withDistance({ awaitingDistance: null }));
      await assertFails(withDistance({ awaitingDistance: 1 }));
    });

    test('An existing entry can be updated with its distance', async () => {
      await assertSucceeds(updateDoc(myEntry(), { swimDistanceMeters: 900, awaitingDistance: false }));
      await assertFails(updateDoc(myEntry(), { swimDistanceMeters: 0 }));
    });
  });

  describe('Who may write', () => {
    test('An anonymous account cannot create, change or delete data', async () => {
      await assertFails(setDoc(doc(anonymousAccount, `users/alice/entries/${untaggedEntryId}`), untaggedEntry));
      await assertFails(updateDoc(doc(anonymousAccount, `users/alice/entries/${entryId}`), { swimDurationMillis: 60000 }));
      await assertFails(deleteDoc(doc(anonymousAccount, `users/alice/entries/${entryId}`)));
      await assertFails(setDoc(doc(anonymousAccount, `users/alice/subscriptions/${subscriptionId}`), subscription));
      await assertFails(setDoc(doc(anonymousAccount, 'users/alice/settings/state'), { activeSubscriptionId: null }));
    });

    test('An account without a sign-in provider cannot write either', async () => {
      await assertFails(setDoc(doc(noProviderAccount, `users/alice/entries/${untaggedEntryId}`), untaggedEntry));
    });

    test('An anonymous account can still read its own data', async () => {
      await assertSucceeds(getDoc(doc(anonymousAccount, `users/alice/entries/${entryId}`)));
    });
  });

  describe('Limits', () => {
    test('Timestamps before the year 2000 are refused', async () => {
      await assertFails(setDoc(myEntry('1_none'), { timestampEpochMilli: 1, subscriptionId: null }));
      await assertFails(setDoc(mySubscription(), { ...subscription, addedAtEpochMilli: 1 }));
    });

    test('Expirations beyond the year 2100 are refused', async () => {
      await assertFails(setDoc(mySubscription(), { ...subscription, expiresAtEpochMilli: 4102444800001 }));
      await assertSucceeds(setDoc(mySubscription(), { ...subscription, expiresAtEpochMilli: 4102444800000 }));
    });

    test('A subscription id longer than 64 characters is refused', async () => {
      const longId = 'a'.repeat(65);
      await assertFails(setDoc(mySubscription(longId), { ...subscription, id: longId }));
      const maxId = 'a'.repeat(64);
      await assertSucceeds(setDoc(mySubscription(maxId), { ...subscription, id: maxId }));
    });

    test('Users can delete their active-subscription setting', async () => {
      await env.withSecurityRulesDisabled(async ctx => {
        await setDoc(doc(ctx.firestore(), 'users/alice/settings/state'), { activeSubscriptionId: subscriptionId });
      });
      await assertSucceeds(deleteDoc(doc(me, 'users/alice/settings/state')));
    });
  });

  describe('Settings', () => {
    const mySettings = (id = 'state') => doc(me, `users/alice/settings/${id}`);

    test('Users can save which subscription is active', async () => {
      await assertSucceeds(setDoc(mySettings(), { activeSubscriptionId: subscriptionId }));
    });

    test('Users can clear the active subscription', async () => {
      await assertSucceeds(setDoc(mySettings(), { activeSubscriptionId: null }));
    });

    test('Users can read their settings', async () => {
      await env.withSecurityRulesDisabled(async ctx => {
        await setDoc(doc(ctx.firestore(), 'users/alice/settings/state'), {
          activeSubscriptionId: subscriptionId,
        });
      });
      await assertSucceeds(getDoc(mySettings()));
    });

    test('Settings must be the state document', async () => {
      await assertFails(setDoc(mySettings('other'), { activeSubscriptionId: subscriptionId }));
    });

    test('Settings need the active subscription key and nothing else', async () => {
      await assertFails(setDoc(mySettings(), {}));
      await assertFails(setDoc(mySettings(), { activeSubscriptionId: null, extra: 1 }));
    });

    test('The active subscription must be a short string or null', async () => {
      await assertFails(setDoc(mySettings(), { activeSubscriptionId: 12 }));
      await assertFails(setDoc(mySettings(), { activeSubscriptionId: '' }));
      await assertFails(setDoc(mySettings(), { activeSubscriptionId: 'x'.repeat(65) }));
    });

    test('Other users cannot read or write my settings', async () => {
      const theirs = doc(other, 'users/alice/settings/state');
      await assertFails(getDoc(theirs));
      await assertFails(setDoc(theirs, { activeSubscriptionId: subscriptionId }));
    });

    test('Signed-out visitors cannot read or write settings', async () => {
      const theirs = doc(anonymous, 'users/alice/settings/state');
      await assertFails(getDoc(theirs));
      await assertFails(setDoc(theirs, { activeSubscriptionId: null }));
    });
  });

  describe('Subscription validation', () => {
    const newId = '9a9a9a9a-0000-4000-8000-000000000003';
    const valid = { ...subscription, id: newId };

    test('Subscriptions must match their document ID', async () => {
      await assertFails(setDoc(mySubscription(newId), subscription));
    });

    test('Subscriptions must have every field, and no others', async () => {
      const { price, ...withoutPrice } = valid;
      await assertFails(setDoc(mySubscription(newId), withoutPrice));
      await assertFails(setDoc(mySubscription(newId), { ...valid, uri: 'content://x' }));
    });

    test('Display names must be 1 to 500 characters', async () => {
      await assertFails(setDoc(mySubscription(newId), { ...valid, displayName: '' }));
      await assertFails(setDoc(mySubscription(newId), { ...valid, displayName: 'x'.repeat(501) }));
      await assertFails(setDoc(mySubscription(newId), { ...valid, displayName: 7 }));
      await assertSucceeds(setDoc(mySubscription(newId), { ...valid, displayName: 'x'.repeat(500) }));
    });

    test('Numbers must be in range and of the right type', async () => {
      await assertFails(setDoc(mySubscription(newId), { ...valid, price: -1 }));
      await assertFails(setDoc(mySubscription(newId), { ...valid, price: 100001 }));
      await assertFails(setDoc(mySubscription(newId), { ...valid, price: '60' }));
      await assertFails(setDoc(mySubscription(newId), { ...valid, maxEntries: 0 }));
      await assertFails(setDoc(mySubscription(newId), { ...valid, maxEntries: 2.5 }));
      await assertFails(setDoc(mySubscription(newId), { ...valid, maxEntries: 100001 }));
      await assertFails(setDoc(mySubscription(newId), { ...valid, expiresAtEpochMilli: 0 }));
      await assertFails(setDoc(mySubscription(newId), { ...valid, addedAtEpochMilli: now + 2 * 24 * 3600 * 1000 }));
      await assertSucceeds(setDoc(mySubscription(newId), { ...valid, price: 0 }));
    });

    test('Subscriptions can be re-saved with new details', async () => {
      await assertSucceeds(setDoc(mySubscription(), { ...subscription, displayName: 'Renamed.pdf', price: 70 }));
    });

    test('The ID and added date of a subscription cannot change', async () => {
      await assertFails(updateDoc(mySubscription(), { addedAtEpochMilli: now - 1000 }));
      await assertFails(updateDoc(mySubscription(), { id: newId }));
    });

    test('Subscriptions cannot be updated into an invalid state', async () => {
      await assertFails(updateDoc(mySubscription(), { displayName: 'x'.repeat(100000) }));
      await assertFails(updateDoc(mySubscription(), { isAdmin: true }));
    });
  });
});
