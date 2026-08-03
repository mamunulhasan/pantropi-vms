-- Withdraws FM_ADMIN -> visitor.request, granted by V15.
--
-- WHY IT IS GOING AWAY: the product prototype's facility role is overview,
-- tenant organizations, tenant users, approval queue, RFID & ACS, calendar
-- and reports. It has no screen for raising a visitor request, and the owner
-- confirmed that is intended rather than an omission in the deck. A facility
-- administrator reviews requests; tenants and the reception desk raise them.
--
-- WHY V15 ADDED IT: to make the create -> approve -> issue path walkable by
-- one sign-in during a demo. V15 said in its own header that this was a demo
-- convenience and not a requirements decision, and that it made an approver
-- into someone who could approve their own submission. This settles it in the
-- direction V2's role description always pointed: "Reviews and approves
-- visitor requests" — reviewing is not raising.
--
-- It also removes a dead end rather than hiding one. FM_ADMIN holds no
-- tenant_id, and SubmitVisitorRequest derives the tenant from the submitter,
-- so the permission could never produce a successful submission anyway — only
-- a 409 explaining that the account is not assigned to a tenant.
--
-- WHAT IS DELIBERATELY KEPT: FM_ADMIN -> credential.issue. That grant is about
-- *reading* the pass an approval already minted (CredentialController is
-- guarded by it), not about minting one. Approval consults no permission.
--
-- A forward-only revocation rather than an edit to V15: V15 is applied
-- everywhere it is going to be applied, and changing an applied script is a
-- checksum failure at the next start.

SET search_path TO vms, public;

DELETE FROM vms.role_permissions rp
 USING vms.roles r, vms.permissions p
 WHERE rp.role_id = r.id
   AND rp.permission_id = p.id
   AND r.code = 'FM_ADMIN'
   AND p.code = 'visitor.request';
