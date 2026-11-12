from helpers import add_member, book, bookings, day, register

SETTINGS = {"name": "Общежитие 5", "description": "3 этаж", "capacity": 5}


def make_admin(owner, code, actor):
    response = owner.put(f"/laundries/{code}/members/{actor.id}/role", json={"role": "admin"})
    assert response.status_code == 200, response.text


# ---------- создание и поиск ----------

def test_creator_becomes_owner(owner, code):
    body = owner.get(f"/laundries/{code}").json()
    assert body["my_role"] == "owner"
    assert body["my_status"] == "active"
    assert body["member_count"] == 1
    assert body["capacity"] == 5
    assert len(code) == 6


def test_my_laundries_lists_memberships_and_requests(client, owner, code):
    applicant = register(client)
    applicant.post(f"/laundries/{code}/join")
    assert [(l["code"], l["my_status"]) for l in owner.get("/laundries").json()] == [(code, "active")]
    assert [(l["code"], l["my_status"]) for l in applicant.get("/laundries").json()] == [(code, "pending")]


def test_search_by_id_ignores_case_and_shows_card_to_outsider(client, code):
    outsider = register(client)
    response = outsider.get(f"/laundries/{code.lower()}")
    assert response.status_code == 200
    assert response.json()["name"] == "Общежитие 5"
    assert response.json()["my_status"] is None


def test_search_unknown_id_returns_404(client):
    assert register(client).get("/laundries/ZZZZZZ").status_code == 404


def test_create_validates_capacity(client):
    actor = register(client)
    assert actor.post("/laundries", json={**SETTINGS, "capacity": 0}).status_code == 422
    assert actor.post("/laundries", json={**SETTINGS, "name": "   "}).status_code == 422


# ---------- заявки ----------

def test_join_creates_pending_request_without_access(client, owner, code):
    applicant = register(client)
    response = applicant.post(f"/laundries/{code}/join")
    assert response.json()["my_status"] == "pending"
    assert applicant.get(f"/laundries/{code}/bookings").status_code == 403
    assert applicant.get(f"/laundries/{code}/members").status_code == 403
    assert owner.get(f"/laundries/{code}").json()["member_count"] == 1  # заявка не считается участником


def test_join_twice_keeps_single_request(client, owner, code):
    applicant = register(client)
    applicant.post(f"/laundries/{code}/join")
    applicant.post(f"/laundries/{code}/join")
    pending = [m for m in owner.get(f"/laundries/{code}/members").json() if m["status"] == "pending"]
    assert len(pending) == 1


def test_only_admins_approve_requests(client, owner, code, member):
    applicant = register(client)
    applicant.post(f"/laundries/{code}/join")
    path = f"/laundries/{code}/members/{applicant.id}/approve"
    assert applicant.post(path).status_code == 403
    assert member.post(path).status_code == 403
    assert owner.post(path).status_code == 200
    assert applicant.get(f"/laundries/{code}/bookings").status_code == 200


def test_admin_can_reject_request(client, owner, code):
    applicant = register(client)
    applicant.post(f"/laundries/{code}/join")
    assert owner.delete(f"/laundries/{code}/members/{applicant.id}").status_code == 200
    assert applicant.get(f"/laundries/{code}").json()["my_status"] is None


def test_applicant_can_withdraw_request(client, owner, code):
    applicant = register(client)
    applicant.post(f"/laundries/{code}/join")
    assert applicant.post(f"/laundries/{code}/leave").status_code == 200
    assert applicant.get(f"/laundries/{code}").json()["my_status"] is None


def test_pending_requests_are_hidden_from_regular_members(client, owner, code, member):
    applicant = register(client)
    applicant.post(f"/laundries/{code}/join")
    assert all(m["status"] == "active" for m in member.get(f"/laundries/{code}/members").json())
    assert any(m["status"] == "pending" for m in owner.get(f"/laundries/{code}/members").json())


# ---------- лимит участников ----------

def test_request_cannot_be_approved_when_laundry_is_full(client, owner):
    code = owner.post("/laundries", json={**SETTINGS, "capacity": 2}).json()["code"]
    add_member(client, owner, code)
    applicant = register(client)
    applicant.post(f"/laundries/{code}/join")
    approve = f"/laundries/{code}/members/{applicant.id}/approve"

    assert owner.post(approve).status_code == 409
    assert owner.patch(f"/laundries/{code}", json={**SETTINGS, "capacity": 3}).status_code == 200
    assert owner.post(approve).status_code == 200
    assert owner.get(f"/laundries/{code}").json()["member_count"] == 3


def test_capacity_cannot_go_below_member_count(owner, code, member):
    assert owner.patch(f"/laundries/{code}", json={**SETTINGS, "capacity": 1}).status_code == 400
    assert owner.patch(f"/laundries/{code}", json={**SETTINGS, "capacity": 2}).status_code == 200


# ---------- настройки и роли ----------

def test_member_cannot_edit_settings(code, member):
    assert member.patch(f"/laundries/{code}", json=SETTINGS).status_code == 403


def test_admin_edits_settings(owner, code, member):
    make_admin(owner, code, member)
    response = member.patch(
        f"/laundries/{code}", json={"name": " Новое имя ", "description": "описание", "capacity": 7}
    )
    assert response.status_code == 200
    assert (response.json()["name"], response.json()["capacity"]) == ("Новое имя", 7)


def test_only_owner_manages_admins(client, owner, code, member):
    other = add_member(client, owner, code)
    role = f"/laundries/{code}/members/{other.id}/role"
    assert member.put(role, json={"role": "admin"}).status_code == 403

    make_admin(owner, code, member)
    assert member.put(role, json={"role": "admin"}).status_code == 403  # админ — не создатель

    assert owner.put(role, json={"role": "admin"}).status_code == 200
    assert owner.put(role, json={"role": "member"}).status_code == 200
    assert owner.put(role, json={"role": "owner"}).status_code == 422


def test_owner_role_cannot_be_changed(owner, code):
    response = owner.put(f"/laundries/{code}/members/{owner.id}/role", json={"role": "member"})
    assert response.status_code == 400


# ---------- удаление участников ----------

def test_removal_rules(client, owner, code, member):
    admin = add_member(client, owner, code)
    make_admin(owner, code, admin)
    other_admin = add_member(client, owner, code)
    make_admin(owner, code, other_admin)

    assert member.delete(f"/laundries/{code}/members/{admin.id}").status_code == 403
    assert admin.delete(f"/laundries/{code}/members/{owner.id}").status_code == 400
    assert admin.delete(f"/laundries/{code}/members/{other_admin.id}").status_code == 403
    assert admin.delete(f"/laundries/{code}/members/{member.id}").status_code == 200
    assert owner.delete(f"/laundries/{code}/members/{other_admin.id}").status_code == 200
    assert member.get(f"/laundries/{code}/bookings").status_code == 403


def test_removing_member_deletes_their_future_bookings(owner, code, member):
    book(member, code, day(1), 10)
    book(owner, code, day(1), 11)
    owner.delete(f"/laundries/{code}/members/{member.id}")
    assert [b["name"] for b in bookings(owner, code)] == ["Аня"]


def test_member_can_leave_but_owner_cannot(owner, code, member):
    book(member, code, day(2), 9)
    assert member.post(f"/laundries/{code}/leave").status_code == 200
    assert bookings(owner, code) == []
    assert owner.post(f"/laundries/{code}/leave").status_code == 400


# ---------- удаление стиралки ----------

def test_only_owner_deletes_laundry(owner, code, member):
    make_admin(owner, code, member)
    book(member, code, day(1), 10)
    assert member.delete(f"/laundries/{code}").status_code == 403
    assert owner.delete(f"/laundries/{code}").status_code == 200
    assert owner.get(f"/laundries/{code}").status_code == 404
    assert member.get("/laundries").json() == []
