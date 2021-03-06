-- This is a plain jane table, with no
-- partition columns and no indexes.
CREATE TABLE VANILLA (
	A	INTEGER NOT NULL,
	B	INTEGER NOT NULL,
	C	INTEGER NOT NULL
);

-- This is a replicated table with an
-- index.
CREATE TABLE VANILLA_IDX (
A	INTEGER NOT NULL,
B	INTEGER NOT NULL,
C	INTEGER NOT NULL
);
CREATE INDEX IVANILLA_IDXA ON VANILLA_IDX (A);
CREATE INDEX IVANILLA_IDXAB ON VANILLA_IDX (A, B);
CREATE INDEX IVANILLA_IDXABC ON VANILLA_IDX (A, B, C);

-- This is a partitioned table with no indexes.
CREATE TABLE VANILLA_PA (
	A	INTEGER NOT NULL,
	B	INTEGER NOT NULL,
	C	INTEGER NOT NULL
);
PARTITION TABLE VANILLA_PA ON COLUMN A;

-- This is a partitioned table with an index
-- on the partition column.
CREATE TABLE VANILLA_PA_IDX (
A	INTEGER NOT NULL,
B	INTEGER NOT NULL,
C	INTEGER NOT NULL
);
PARTITION TABLE VANILLA_PA_IDX ON COLUMN A;
CREATE INDEX IVANILLA_PA_IDXA ON VANILLA_PA_IDX (A);
CREATE INDEX IVANILLA_PA_IDXAB ON VANILLA_PA_IDX (A, B);
CREATE INDEX IVANILLA_PA_IDXABC ON VANILLA_PA_IDX (A, B, C);

-- This is a partitioned table with no indexes at all.
-- See VANILLA_PB_IDX for why this might be needed.
CREATE TABLE VANILLA_PB (
A	INTEGER NOT NULL,
B	INTEGER NOT NULL,
C	INTEGER NOT NULL
);
PARTITION TABLE VANILLA_PB ON COLUMN B;

-- This is a partitioned table with no index on the
-- partition column, but with an index on some
-- other column.
CREATE TABLE VANILLA_PB_IDX (
A	INTEGER NOT NULL,
B	INTEGER NOT NULL,
C	INTEGER NOT NULL
);
PARTITION TABLE VANILLA_PB_IDX ON COLUMN B;
CREATE INDEX IVANILLA_PB_IDXA ON VANILLA_PB_IDX (A);


CREATE TABLE O4 (
 ID    BIGINT,
 CTR   BIGINT
 );
CREATE INDEX O4_CTR_PLUS_100 ON O4 (CTR + 100);  

--
-- This is from sqlcoverage.
--
CREATE TABLE P_DECIMAL (
ID INTEGER NOT NULL,
CASH DECIMAL NOT NULL,
CREDIT DECIMAL,
RATIO FLOAT,
PRIMARY KEY (ID)
);
PARTITION TABLE P_DECIMAL ON COLUMN ID;

