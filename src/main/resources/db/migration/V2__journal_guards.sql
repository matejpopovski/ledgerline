-- These guards sit below the application on purpose: they hold for a psql session, a
-- bad migration and a bug in the service alike. SQLSTATE class LL is ours, so callers
-- can tell a guard firing from any other failure.

CREATE FUNCTION ledger_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only: % rejected', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'LL001',
              HINT = 'Correct a posted entry by posting its reversal.';
END;
$$;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER postings_no_truncate
    BEFORE TRUNCATE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();

-- Runs at COMMIT, when every leg of the entry exists. It hangs off both tables so an
-- entry with no postings fails too, and so does a later attempt to add a leg to an
-- entry that has already been committed.
CREATE FUNCTION ledger_assert_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    target uuid;
    legs   bigint;
    net    numeric;
BEGIN
    IF TG_TABLE_NAME = 'postings' THEN
        target := NEW.entry_id;
    ELSE
        target := NEW.id;
    END IF;

    SELECT count(*), coalesce(sum(CASE direction WHEN 'D' THEN amount_minor ELSE -amount_minor END), 0)
    INTO legs, net
    FROM postings
    WHERE entry_id = target;

    IF legs < 2 OR net <> 0 THEN
        RAISE EXCEPTION 'journal entry % does not balance: % legs, net %', target, legs, net
            USING ERRCODE = 'LL002';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_entry_balanced();

CREATE CONSTRAINT TRIGGER journal_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_entry_balanced();

-- Flipping normal_side would silently invert every historical balance, and changing
-- currency would reinterpret them, so neither may change once the account exists.
CREATE FUNCTION ledger_reject_account_identity_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.currency <> OLD.currency OR NEW.normal_side <> OLD.normal_side THEN
        RAISE EXCEPTION 'account % cannot change currency or normal side', OLD.id
            USING ERRCODE = 'LL003';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_identity_fixed
    BEFORE UPDATE OF currency, normal_side ON accounts
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_account_identity_change();
