-- Baseline: the schema Hibernate generated from the entities as of 2026-10-16.
-- Existing databases are baselined at this version and never run it (see ADR 0001).

CREATE TABLE public.events (
    round_number integer,
    sequence_number integer,
    created_at timestamp(6) with time zone NOT NULL,
    actor_id uuid,
    game_id uuid,
    id uuid NOT NULL,
    event_type character varying(50) NOT NULL,
    data jsonb NOT NULL,
    CONSTRAINT events_event_type_check CHECK (((event_type)::text = ANY ((ARRAY['GAME_CREATED'::character varying, 'PLAYER_JOINED'::character varying, 'PLAYER_READY'::character varying, 'ROUND_STARTED'::character varying, 'BET_LIMIT_SET'::character varying, 'BET_PLACED'::character varying, 'HOUSE_ROLLED'::character varying, 'PLAYER_ROLLED'::character varying, 'ROUND_COMPLETED'::character varying, 'GAME_COMPLETED'::character varying, 'GAME_ADVANCED'::character varying, 'DEPOSIT'::character varying, 'WITHDRAWAL'::character varying])::text[])))
);

CREATE TABLE public.games (
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    started_at timestamp(6) with time zone,
    creator_id uuid NOT NULL,
    id uuid NOT NULL,
    status character varying(255) NOT NULL,
    metadata jsonb,
    CONSTRAINT games_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'COMPLETED'::character varying])::text[])))
);

CREATE TABLE public.transactions (
    amount numeric(19,4) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    event_id uuid NOT NULL,
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    type character varying(30) NOT NULL,
    CONSTRAINT transactions_type_check CHECK (((type)::text = ANY ((ARRAY['BET_HOLD'::character varying, 'BET_RELEASE'::character varying, 'BET_WIN'::character varying, 'BET_LOSS'::character varying, 'BET_WIN_DOUBLE'::character varying, 'BET_LOSS_DOUBLE'::character varying, 'DEPOSIT'::character varying, 'WITHDRAWAL'::character varying])::text[])))
);

CREATE TABLE public.user_roles (
    user_id uuid NOT NULL,
    role character varying(255),
    CONSTRAINT user_roles_role_check CHECK (((role)::text = ANY ((ARRAY['PLAYER'::character varying, 'ADMIN'::character varying, 'TEST_PLAYER'::character varying])::text[])))
);

CREATE TABLE public.users (
    balance numeric(38,2),
    join_date timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    auth_id character varying(255) NOT NULL,
    email character varying(255) NOT NULL,
    nickname character varying(255),
    username character varying(255)
);

ALTER TABLE ONLY public.events
    ADD CONSTRAINT events_game_id_actor_id_round_number_key UNIQUE (game_id, actor_id, round_number);

ALTER TABLE ONLY public.events
    ADD CONSTRAINT events_game_id_sequence_number_key UNIQUE (game_id, sequence_number);

ALTER TABLE ONLY public.events
    ADD CONSTRAINT events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.games
    ADD CONSTRAINT games_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.transactions
    ADD CONSTRAINT transactions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_auth_id_key UNIQUE (auth_id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_username_key UNIQUE (username);

CREATE UNIQUE INDEX uq_events_actor_round ON public.events USING btree (game_id, actor_id, round_number);

ALTER TABLE ONLY public.games
    ADD CONSTRAINT fk56shp19ym57w15cklkgd2gdj2 FOREIGN KEY (creator_id) REFERENCES public.users(id);

ALTER TABLE ONLY public.transactions
    ADD CONSTRAINT fke01y6qhe8lfkj74f0nac6mbmk FOREIGN KEY (event_id) REFERENCES public.events(id);

ALTER TABLE ONLY public.events
    ADD CONSTRAINT fkgllk7v39w1w7cdxa9c819lnjn FOREIGN KEY (game_id) REFERENCES public.games(id);

ALTER TABLE ONLY public.user_roles
    ADD CONSTRAINT fkhfh9dx7w3ubf1co1vdev94g3f FOREIGN KEY (user_id) REFERENCES public.users(id);

ALTER TABLE ONLY public.transactions
    ADD CONSTRAINT fkqwv7rmvc8va8rep7piikrojds FOREIGN KEY (user_id) REFERENCES public.users(id);

